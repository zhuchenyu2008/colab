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

## v0.3.0: native Vulkan SCRFD detector

- Adds a real Vulkan GPU path for SCRFD-10G face detection using Tencent ncnn and the official `nihui/ncnn-android-scrfd` converted 10g_kps weights.
- Uses Android NDK JNI for the ncnn GPU detector; returns bounding boxes and five facial landmarks, then reuses the existing Java 112x112 ArcFace alignment and ONNX embedding model.
- **Only face detection** is Vulkan GPU accelerated. ArcFace recognition, frame decoding, and some postprocessing remain CPU, so overall processing time may still be significant. Actual performance on OnePlus 12 is not measured yet.
- If ncnn Vulkan cannot initialize, automatically falls back to ONNX Runtime NNAPI/CPU path.
- ncnn Vulkan binaries and detection weights are retrieved during GitHub Actions build, not committed into source. See the CI workflow for exact upstream URLs.
- Torch/ONNX input preprocessing and model variants may vary; do not assume scores exactly match v0.2.0. Validate with authorized test data.
- Build downloads 40MB ncnn Android Vulkan SDK and ~8MB SCRFD model; NDK and CMake required.
- Debug APK signing may differ across builds; uninstall may be required, clearing the previously downloaded ArcFace pack.

## v0.4.0: sequential video decoding

- Default MediaExtractor + MediaCodec sequential decoding avoids repeatedly seeking for each sample. Only sampled frames are converted to RGB, downscaled to the same maximum 960-pixel dimension. SDR YUV420 crop, stride, range, color matrices, and rotation are handled explicitly.
- The existing Vulkan SCRFD, CPU ArcFace, similarity threshold, and sampling interval settings are unchanged. Sequential sampling uses actual decoded presentation timestamps, so selected frames/timestamps and numeric scores may differ slightly from the legacy nearest-timestamp path.
- A UI checkbox retains MediaMetadataRetriever for A/B comparisons. Unsupported formats, HDR, timeout, or decoder output failure trigger a clean restart in legacy mode. Inference errors do not cause a decode restart.
- Results identify the selected decoder (including the Android hardware-acceleration flag where available), decoded frame count, sampled frame count, and timing. No phone-specific speed claim until measured. Debug signing may require uninstalling the old APK.
