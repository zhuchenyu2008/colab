import importlib
import os
from pathlib import Path

from starlette.testclient import TestClient


def load_app(tmp_path: Path):
    os.environ["HEALTH_DB"] = str(tmp_path / "health.db")
    os.environ["INGEST_TOKEN"] = "test-ingest-token-0123456789"
    os.environ["MCP_TOKEN"] = "test-mcp-token-0123456789"
    import health_bridge.app as module
    return importlib.reload(module)


def sample_payload():
    return {
        "schema_version": 1,
        "device_id": "device-12345678",
        "timezone": "Asia/Shanghai",
        "generated_at": "2026-08-19T00:00:00Z",
        "days": [
            {
                "date": "2026-08-19",
                "steps": 8123,
                "distance_m": 6021.5,
                "active_calories_kcal": 421.0,
                "total_calories_kcal": 1800.0,
                "avg_heart_rate_bpm": 76,
                "min_heart_rate_bpm": 52,
                "max_heart_rate_bpm": 161,
                "heart_rate_measurements": 400,
                "resting_heart_rate_bpm": 60,
                "avg_spo2_pct": 97.2,
                "min_spo2_pct": 94.0,
                "max_spo2_pct": 99.0,
                "sleep_minutes": 431,
                "weight_kg": 55.2,
                "source_packages": ["com.example.health"],
            }
        ],
    }


def test_health_and_ingest(tmp_path):
    module = load_app(tmp_path)
    with TestClient(module.app) as client:
        assert client.get("/healthz").status_code == 200
        assert client.post("/api/v1/ingest", json=sample_payload()).status_code == 401
        response = client.post(
            "/api/v1/ingest",
            json=sample_payload(),
            headers={"Authorization": "Bearer test-ingest-token-0123456789"},
        )
        assert response.status_code == 200
        assert response.json()["days_upserted"] == 1

    latest = module.get_latest_health_day()
    assert latest["date"] == "2026-08-19"
    assert latest["steps"] == 8123
    assert module.get_health_day("2026-08-18")["status"] == "no_data"
    assert module.get_health_trend("steps", 7)["average"] == 8123


def test_rejects_wrong_timezone(tmp_path):
    module = load_app(tmp_path)
    payload = sample_payload()
    payload["timezone"] = "UTC"
    with TestClient(module.app) as client:
        response = client.post(
            "/api/v1/ingest",
            json=payload,
            headers={"Authorization": "Bearer test-ingest-token-0123456789"},
        )
        assert response.status_code == 400
