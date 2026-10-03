# Health Connect → ChatGPT Bridge

把 Android Health Connect 中已经存在的健康数据安全同步到自己的服务器，并通过只读 MCP 工具提供给 ChatGPT。

## 架构

```text
华为/OPPO/其他设备
  → 厂商健康 App
  → Health Connect
  → Android Bridge App
  → HTTPS /api/v1/ingest
  → SQLite
  → /mcp
  → ChatGPT
```

Android 端按北京时间（Asia/Shanghai）汇总自然日数据。目前 MVP 同步：步数、距离、活动热量、总热量、平均/最低/最高心率、静息心率、血氧、睡眠时长、体重和可识别的数据来源包名。

## 1. 服务端

```bash
cd server
python -m venv .venv
. .venv/bin/activate
pip install -e '.[test]'
export INGEST_TOKEN='换成至少32位随机字符串'
export MCP_TOKEN='换成另一段至少32位随机字符串'
export HEALTH_DB='./data/health.db'
uvicorn health_bridge.app:app --host 0.0.0.0 --port 8787
```

生产环境必须放在 HTTPS 反向代理后面。探活：`GET /healthz`。Android 写入：`POST /api/v1/ingest`，Bearer 使用 `INGEST_TOKEN`。MCP：`/mcp`，Bearer 使用 `MCP_TOKEN`。

Docker：

```bash
docker build -t health-chat-bridge server
mkdir -p data
docker run -d --name health-chat-bridge \
  -p 127.0.0.1:8787:8787 \
  -v "$PWD/data:/data" \
  -e HEALTH_DB=/data/health.db \
  -e INGEST_TOKEN='...' \
  -e MCP_TOKEN='...' \
  health-chat-bridge
```

## 2. Android

CI 会构建 Debug APK。安装后：

1. 填服务器 HTTPS 根地址，例如 `https://health.example.com`。
2. 填 `INGEST_TOKEN`。
3. 保存配置。
4. 点“授权 Health Connect”，尽量授予所有读取权限和后台读取权限。
5. 点“立即同步最近 7 天”验证。
6. 点“开启每小时后台同步”。后台任务每次回补最近 3 个自然日，处理厂商延迟写入 Health Connect 的情况。

服务器地址会保存在应用私有配置中；令牌使用 Android Keystore AES-GCM 加密后保存。应用关闭 Android Backup，且不会申请任何 Health Connect 写权限。

## 3. MCP 工具

- `get_latest_health_day()`：最近有数据的一天
- `get_health_day(date)`：指定北京时间自然日
- `get_health_range(start_date, end_date)`：范围查询，最多 90 天
- `get_health_trend(metric, days)`：趋势查询
- `get_bridge_status()`：最近同步状态

允许的趋势指标：`steps`、`distance_m`、`active_calories_kcal`、`total_calories_kcal`、`avg_heart_rate_bpm`、`resting_heart_rate_bpm`、`avg_spo2_pct`、`sleep_minutes`、`weight_kg`。

## 安全边界

- Android 端只读 Health Connect。
- 写入 API 与 MCP 使用不同令牌。
- 服务端不会把令牌写入数据库或日志。
- MCP 工具全部只读，不提供删除/修改健康记录能力。
- SQLite 默认只存日汇总，不上传原始逐秒心率点，减少隐私暴露和数据库体积。

## 当前 MVP 的边界

Health Connect 中没有的数据无法凭空同步。不同厂商写入的数据类型也可能不同，所以某些字段为 `null` 属于正常情况。下一阶段可继续增加运动会话、睡眠阶段、HRV、VO₂max 等数据类型。