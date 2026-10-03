from __future__ import annotations

import hmac
import json
import os
import sqlite3
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any, Literal

from mcp.server import MCPServer
from starlette.requests import Request
from starlette.responses import JSONResponse, PlainTextResponse

DB_PATH = os.getenv("HEALTH_DB", "./data/health.db")
INGEST_TOKEN = os.getenv("INGEST_TOKEN", "")
MCP_TOKEN = os.getenv("MCP_TOKEN", "")

ALLOWED_METRICS = {
    "steps",
    "distance_m",
    "active_calories_kcal",
    "total_calories_kcal",
    "avg_heart_rate_bpm",
    "resting_heart_rate_bpm",
    "avg_spo2_pct",
    "sleep_minutes",
    "weight_kg",
}


def _connect() -> sqlite3.Connection:
    if DB_PATH != ":memory:":
        Path(DB_PATH).expanduser().resolve().parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH, timeout=15)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


def _init_db() -> None:
    with _connect() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS health_days (
                device_id TEXT NOT NULL,
                date TEXT NOT NULL,
                timezone TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                source_generated_at TEXT NOT NULL,
                received_at TEXT NOT NULL,
                PRIMARY KEY (device_id, date)
            );
            CREATE INDEX IF NOT EXISTS idx_health_days_date ON health_days(date);

            CREATE TABLE IF NOT EXISTS sync_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                received_at TEXT NOT NULL,
                day_count INTEGER NOT NULL
            );
            """
        )


_init_db()


def _authorized(request: Request, token: str) -> bool:
    if not token:
        return False
    supplied = request.headers.get("authorization", "")
    prefix = "Bearer "
    if not supplied.startswith(prefix):
        return False
    return hmac.compare_digest(supplied[len(prefix):], token)


def _valid_iso_date(value: str) -> str:
    parsed = date.fromisoformat(value)
    return parsed.isoformat()


def _normalize_day(raw: dict[str, Any]) -> dict[str, Any]:
    result = {
        "date": _valid_iso_date(str(raw["date"])),
        "steps": _number_or_none(raw.get("steps"), int),
        "distance_m": _number_or_none(raw.get("distance_m"), float),
        "active_calories_kcal": _number_or_none(raw.get("active_calories_kcal"), float),
        "total_calories_kcal": _number_or_none(raw.get("total_calories_kcal"), float),
        "avg_heart_rate_bpm": _number_or_none(raw.get("avg_heart_rate_bpm"), int),
        "min_heart_rate_bpm": _number_or_none(raw.get("min_heart_rate_bpm"), int),
        "max_heart_rate_bpm": _number_or_none(raw.get("max_heart_rate_bpm"), int),
        "heart_rate_measurements": _number_or_none(raw.get("heart_rate_measurements"), int),
        "resting_heart_rate_bpm": _number_or_none(raw.get("resting_heart_rate_bpm"), int),
        "avg_spo2_pct": _number_or_none(raw.get("avg_spo2_pct"), float),
        "min_spo2_pct": _number_or_none(raw.get("min_spo2_pct"), float),
        "max_spo2_pct": _number_or_none(raw.get("max_spo2_pct"), float),
        "sleep_minutes": _number_or_none(raw.get("sleep_minutes"), int),
        "weight_kg": _number_or_none(raw.get("weight_kg"), float),
        "source_packages": sorted({str(x)[:200] for x in raw.get("source_packages", []) if x}),
    }
    return result


def _number_or_none(value: Any, cast: type[int] | type[float]) -> int | float | None:
    if value is None:
        return None
    number = cast(value)
    if isinstance(number, float) and (number != number or abs(number) == float("inf")):
        raise ValueError("non-finite number")
    return number


def _upsert_payload(payload: dict[str, Any]) -> int:
    if int(payload.get("schema_version", 0)) != 1:
        raise ValueError("unsupported schema_version")
    device_id = str(payload.get("device_id", "")).strip()
    if not 8 <= len(device_id) <= 128:
        raise ValueError("invalid device_id")
    tz = str(payload.get("timezone", ""))
    if tz != "Asia/Shanghai":
        raise ValueError("timezone must be Asia/Shanghai")
    generated_at = str(payload.get("generated_at", ""))
    datetime.fromisoformat(generated_at.replace("Z", "+00:00"))
    days = payload.get("days")
    if not isinstance(days, list) or not 1 <= len(days) <= 90:
        raise ValueError("days must contain 1..90 items")

    normalized = [_normalize_day(dict(day)) for day in days]
    received_at = datetime.now(timezone.utc).isoformat()
    with _connect() as conn:
        for day in normalized:
            conn.execute(
                """
                INSERT INTO health_days(device_id, date, timezone, payload_json, source_generated_at, received_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(device_id, date) DO UPDATE SET
                    timezone=excluded.timezone,
                    payload_json=excluded.payload_json,
                    source_generated_at=excluded.source_generated_at,
                    received_at=excluded.received_at
                """,
                (device_id, day["date"], tz, json.dumps(day, ensure_ascii=False), generated_at, received_at),
            )
        conn.execute(
            "INSERT INTO sync_events(device_id, received_at, day_count) VALUES (?, ?, ?)",
            (device_id, received_at, len(normalized)),
        )
    return len(normalized)


def _rows(start: str | None = None, end: str | None = None, limit: int = 90) -> list[dict[str, Any]]:
    clauses: list[str] = []
    params: list[Any] = []
    if start:
        clauses.append("date >= ?")
        params.append(_valid_iso_date(start))
    if end:
        clauses.append("date <= ?")
        params.append(_valid_iso_date(end))
    where = (" WHERE " + " AND ".join(clauses)) if clauses else ""
    query = f"SELECT payload_json, received_at FROM health_days{where} ORDER BY date DESC LIMIT ?"
    params.append(max(1, min(limit, 90)))
    with _connect() as conn:
        rows = conn.execute(query, params).fetchall()
    output = []
    for row in rows:
        item = json.loads(row["payload_json"])
        item["bridge_received_at"] = row["received_at"]
        output.append(item)
    return output


mcp = MCPServer(
    "Health Connect Bridge",
    instructions="Read-only personal health summaries synced from Android Health Connect. Dates use Asia/Shanghai (Beijing time). Never invent missing measurements.",
)


@mcp.tool()
def get_latest_health_day() -> dict[str, Any]:
    """Return the most recent synced Beijing-time health day."""
    rows = _rows(limit=1)
    return rows[0] if rows else {"status": "no_data"}


@mcp.tool()
def get_health_day(date: str) -> dict[str, Any]:
    """Return one Beijing-time calendar day in YYYY-MM-DD format."""
    day = _valid_iso_date(date)
    rows = _rows(start=day, end=day, limit=1)
    return rows[0] if rows else {"status": "no_data", "date": day}


@mcp.tool()
def get_health_range(start_date: str, end_date: str) -> list[dict[str, Any]]:
    """Return health summaries for an inclusive Beijing-time date range, maximum 90 days."""
    start = date.fromisoformat(_valid_iso_date(start_date))
    end = date.fromisoformat(_valid_iso_date(end_date))
    if end < start:
        raise ValueError("end_date must be on or after start_date")
    if (end - start).days > 89:
        raise ValueError("range cannot exceed 90 days")
    return list(reversed(_rows(start=start.isoformat(), end=end.isoformat(), limit=90)))


@mcp.tool()
def get_health_trend(
    metric: Literal[
        "steps",
        "distance_m",
        "active_calories_kcal",
        "total_calories_kcal",
        "avg_heart_rate_bpm",
        "resting_heart_rate_bpm",
        "avg_spo2_pct",
        "sleep_minutes",
        "weight_kg",
    ],
    days: int = 7,
) -> dict[str, Any]:
    """Return a simple time series for one supported metric over the latest N synced days."""
    if metric not in ALLOWED_METRICS:
        raise ValueError("unsupported metric")
    if not 1 <= days <= 90:
        raise ValueError("days must be 1..90")
    rows = list(reversed(_rows(limit=days)))
    points = [{"date": row["date"], "value": row.get(metric)} for row in rows]
    numeric = [p["value"] for p in points if isinstance(p["value"], (int, float))]
    return {
        "metric": metric,
        "points": points,
        "average": (sum(numeric) / len(numeric)) if numeric else None,
        "min": min(numeric) if numeric else None,
        "max": max(numeric) if numeric else None,
    }


@mcp.tool()
def get_bridge_status() -> dict[str, Any]:
    """Return last ingest time, synced-day count and data date range without exposing secrets."""
    with _connect() as conn:
        last = conn.execute(
            "SELECT device_id, received_at, day_count FROM sync_events ORDER BY id DESC LIMIT 1"
        ).fetchone()
        stats = conn.execute(
            "SELECT COUNT(*) AS n, MIN(date) AS first_date, MAX(date) AS last_date FROM health_days"
        ).fetchone()
    return {
        "last_sync": dict(last) if last else None,
        "stored_days": stats["n"],
        "first_date": stats["first_date"],
        "last_date": stats["last_date"],
        "timezone": "Asia/Shanghai",
    }


@mcp.custom_route("/healthz", methods=["GET"])
async def healthz(_: Request) -> JSONResponse:
    return JSONResponse({"status": "ok", "service": "health-connect-chatgpt-bridge"})


@mcp.custom_route("/api/v1/ingest", methods=["POST"])
async def ingest(request: Request) -> JSONResponse:
    if not _authorized(request, INGEST_TOKEN):
        return JSONResponse({"error": "unauthorized"}, status_code=401)
    try:
        body = await request.body()
        if len(body) > 1_000_000:
            return JSONResponse({"error": "payload_too_large"}, status_code=413)
        payload = json.loads(body)
        count = _upsert_payload(payload)
        return JSONResponse({"ok": True, "days_upserted": count})
    except (ValueError, TypeError, KeyError, json.JSONDecodeError) as exc:
        return JSONResponse({"error": "invalid_payload", "detail": str(exc)[:300]}, status_code=400)


_inner_app = mcp.streamable_http_app(stateless_http=True, json_response=True)


class MCPBearerGate:
    def __init__(self, inner):
        self.inner = inner

    async def __call__(self, scope, receive, send):
        if scope.get("type") == "http" and scope.get("path", "").startswith("/mcp"):
            if not MCP_TOKEN:
                response = PlainTextResponse("MCP_TOKEN is not configured", status_code=503)
                await response(scope, receive, send)
                return
            headers = {k.decode("latin1").lower(): v.decode("latin1") for k, v in scope.get("headers", [])}
            supplied = headers.get("authorization", "")
            prefix = "Bearer "
            if not supplied.startswith(prefix) or not hmac.compare_digest(supplied[len(prefix):], MCP_TOKEN):
                response = PlainTextResponse("Unauthorized", status_code=401, headers={"WWW-Authenticate": "Bearer"})
                await response(scope, receive, send)
                return
        await self.inner(scope, receive, send)


app = MCPBearerGate(_inner_app)
