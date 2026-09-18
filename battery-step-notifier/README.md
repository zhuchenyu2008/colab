# BatteryStepNotifier

个人使用的原生 Android 电量阶梯提醒工具。

- 充电时每提升 1%–20%（用户可调）发送一次提醒
- 基于 Android 系统电池广播，不进行高频轮询
- 使用前台服务保持后台监听
- 开机后可恢复已启用的监测
- 不使用网络、定位或 WakeLock

本分支通过 GitHub Actions 使用 JDK 17、Android SDK 36、AGP 8.13.2 与 Gradle 8.13 构建 APK。
