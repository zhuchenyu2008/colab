# MediaScribe Android

A native Android app for long-form video-to-MP3 and offline Chinese speech transcription.

## Features
- Extracts MP3 (192 kbit/s, stereo, 44.1 kHz) from user-selected video using FFmpeg.
- Locally transcribes MP3, M4A, WAV, and other audio formats with Whisper.
- Splits hour-long files into ~60-second 16 kHz mono WAV chunks to bound peak decoding memory.
- Allows downloading multilingual Whisper Tiny (~75 MB) or Base (~142 MB) on first use.
- Saves MP3 under `Music/MediaScribe`; TXT + SRT under `Download/MediaScribe`.
- Background work uses a foreground service; no upload, no API key.
- Application ID: `cn.zhuchenyu.mediascribe`, `arm64-v8a`, Android 8+.

## Build
```sh
gradle -p MediaScribe :app:assembleDebug
```

Push to `media-scribe-android` to trigger GitHub Actions. Download the `MediaScribe-v1.0.0-arm64` artifact, unzip, and install the APK. The Actions workflow reuses a **public, deterministic test key** so updates can install over the previous APK. This key is not private: do not use it for sensitive production distributions.

## Limitations
- Long audio may take much longer than its playback duration on slower phones; speech recognition is CPU-intensive.
- Audio is split at approximately 60-second boundaries. Words crossing the boundary may need minor manual correction.
- No simultaneous tasks: a single foreground service serializes conversion, model download and transcription.
- During transcription, leave enough free space for temporary PCM WAV data (~115 MB for one hour), the model, and the final output. Allow additional space for Android's internal decoding buffers.
- Initial model download requires network access. For best Chinese accuracy start with Base; Tiny is faster but usually less accurate.
- Recognition language is currently fixed to Mandarin Chinese; mixed-language conversations may lose quality.
- Android file selection uses SAF; output uses MediaStore (Android 10+) rather than broad storage permissions.
- The 32-bit ABI and emulator are not bundled.
- Earlier Android 8/9 devices save output to app-specific external files instead of the shared collections.
- Test signing key is shared publicly, so this APK is for personal/dev use only.

## Dependencies and licenses
- FFmpegKit maintained audio artifact: LGPL-3.0 (contains MP3 LAME codec).
- Whisper Android AAR / whisper.cpp: MIT; model weights downloaded independently.
- Kotlin coroutines: Apache-2.0.
