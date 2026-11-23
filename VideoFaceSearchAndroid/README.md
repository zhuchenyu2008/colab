# Video Face Search Android — first native port

This project ports the **core matching pipeline** of [DolphinBit/video-face-search](https://github.com/DolphinBit/video-face-search) to Android (Java, Android SDK, ONNX Runtime).
It is a new native Android app, **not** an APK produced from Python/PyQt6.

## Features in 0.1.0

- System file picker for reference photo and local video
- First-launch download and local caching of official InsightFace buffalo_l pack (SCRFD det_10g + ArcFace w600k_r50)
- Same 0.48 normalized cosine similarity default threshold; configurable frame interval
- Native MediaMetadataRetriever sampling, SCRFD detection + 5-point alignment + ArcFace embeddings
- Group matches into approximate time ranges, show match counts/scores
- Export candidate MP4 segments with Android MediaExtractor and MediaMuxer
- After first download, inference runs completely on device using ONNX Runtime **CPU** execution (GPU/NPU optimization intentionally deferred)

## Build

Use GitHub Actions workflow **Build Video Face Search Android APK**, branch `video-face-search-android`, or:
```bash
gradle -p VideoFaceSearchAndroid :app:assembleDebug
```
Install the output `VideoFaceSearchAndroid/app/build/outputs/apk/debug/app-debug.apk`.

## Important limitations

- First model download ~200MB, and requires substantially more free space during extraction.
- buffalo_l weights are distributed by InsightFace for non-commercial research; no model weights are bundled into the APK.
- CPU inference is heavy, especially with 640x640 SCRFD 10G, so scanning can be slow and warm the phone.
- Initial port uses original-like frame sampling and similarity + timestamp merge; no person tracking, anti-spoofing or confidence calibration.
- Fixed image detector input 640x640; small or blurry faces may be missed.
- MP4 clips are cut using MediaExtractor/MediaMuxer, may not be frame-accurate or compatible with every codec.
- Candidate matches are not verified identity; for authorized and consented datasets only.
- Not validated on physical OnePlus 12 hardware yet.

Source algorithm inspiration: InsightFace SCRFD and ArcFace inference, original DolphinBit application pipeline. This project does not contain third-party pretrained weights.

## v0.2.0 Android hardware acceleration update

- On-device **NNAPI** Execution Provider is requested by default for both SCRFD and ArcFace ONNX models.
- NNAPI CPU fallback is disabled, but unsupported graph nodes can still execute in ONNX Runtime's CPU provider.
- A user toggle allows CPU-only mode for baseline comparisons and workarounds.
- Records execution-provider events via ONNX Runtime profiling; **hardware assignment is verified by trace, not merely provider registration**. NNAPI does not identify whether the actual target is Adreno GPU or Snapdragon NPU.
- For high-resolution sources, reduces decoded bitmap resolution to a 960-pixel maximum dimension, then runs the existing 640x640 detector. Video decoding itself remains Android MediaMetadataRetriever based.
- Adds decode, model inference, and total elapsed time readouts.
- This is **not a direct Vulkan GPU backend**; actual acceleration availability is dependent on Android NNAPI drivers and compatibility with model operators. If traces show zero NNAPI ops, do not claim GPU acceleration.
- v0.1.0 and v0.2.0 are GitHub Actions *debug-signed* using runner-created keys. Android may require uninstalling old v0.1.0 before installing v0.2.0 due to signing-key mismatch; this removes the original app's cached model.
