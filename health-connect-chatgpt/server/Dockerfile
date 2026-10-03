FROM python:3.12-slim

WORKDIR /app
COPY pyproject.toml ./
COPY health_bridge ./health_bridge
RUN pip install --no-cache-dir .

RUN useradd --create-home --uid 10001 bridge && mkdir -p /data && chown -R bridge:bridge /data /app
USER bridge

ENV HEALTH_DB=/data/health.db
EXPOSE 8787
CMD ["uvicorn", "health_bridge.app:app", "--host", "0.0.0.0", "--port", "8787", "--proxy-headers"]
