# Camera Scanner Android

A local-only, authorized Wi-Fi device discovery utility. Java/native Android UI, Android 8+; no cloud service or external dependencies. Quick/deep mode; foreground recurring monitoring; RTSP OPTIONS (not video access), ONVIF/WS-Discovery, SSDP, mDNS, TCP and HTTP response metadata. No authentication attempts. Scope is RFC1918 IPv4 Wi-Fi, maximum current /24. Rule scores are not probabilities; an ONVIF video transmitter could be an encoder or NVR, and absence of results does not establish absence of cameras. HTTPS/other ports are TCP-only. mDNS may miss simultaneous service resolutions. The Android 17 local-network permission behavior is not device-tested; targetSdk 35 is intentionally used for first compatibility build.

Build with Gradle 8.11.1/JDK17/API35. GitHub Actions produces a release APK with a dedicated persistent signing key if both secrets CAMERA_SIGNING_KEYSTORE_B64 and CAMERA_SIGNING_PASSWORD are configured; otherwise the artifact contains an unsigned APK and the signing tool for offline signing. Never commit the key or its password. Keep applicationId com.zhuchenyu.camerascanner and the same certificate across upgrades; increment versionCode every release. The certificate SHA-256 is pinned in CI. Save the separately provided private backup.

## v1.0.1
RTSP TCP-open evidence now remains visible independently of protocol handshake; scans include 8554 and 10554 in quick mode; wider /23 coverage, dual-format RTSP OPTIONS, longer timeouts, and observable per-scan TCP counters. No credentials are ever requested. Scores still represent heuristic rules only.

## v1.0.2
Improved Wi-Fi route selection and VPN-aware network binding; faster and deeper scan have explicit ranges; additional potential RTSP ports; visible IP, gateway, port list, TCP attempts and outcomes in the app; avoids cancelling active scans when the activity temporarily moves off screen. On current firmware local network access may be filtered; report will explain zero-response scans. No credentials, no cloud.

## v1.0.3
Addresses universal socket failures: records actual exception type/message and transport stage, tries a regular network route when Wi-Fi-bound sockets cannot connect, exposes VPN routing and exception diagnostics in-app and JSON exports. Still needs real device verification.

## v1.0.4
**Security and accuracy correction**: removed the unbound/default-network fallback that fed every local IP through VPN traffic interception and produced nonsensical port-open results. Explicit Wi-Fi connection preflight fails closed upon EPERM; shows a clear VPN/per-app exclusion remedy; no invented camera candidates. Only ONVIF video devices, RTSP protocol responses and mDNS video-service discoveries count as confirmed. Open TCP ports alone are not camera detections. Requires physical Wi-Fi connectivity, and may require excluding this app from a VPN or disabling 'Block connections without VPN'. Not tested on a real device.

## v1.0.5
Evidence-first classification: the main results show only ONVIF NetworkVideoTransmitter discovery or verified RTSP response; mDNS service advertisements are labeled unverified; HTTP/TCP services are hidden behind an explicit toggle and never counted as cameras. Removes heuristic confidence scores and adds JSON category flags. A verified video endpoint may still be an encoder or recorder, not necessarily a physical camera.
