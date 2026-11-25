package cn.zhuchenyu.videofacesearch;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.ClipboardManager;
import android.view.*;
import android.widget.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Main screen for offline video search and the live camera entry point.
 * The layout is deliberately built from platform views so the APK keeps its
 * small dependency surface and remains usable on the existing signed build.
 */
public final class MainActivity extends Activity {
    private static final int BG = 0xFF080D15;
    private static final int PANEL = 0xFF111B29;
    private static final int TEXT = 0xFFF3F7FC;
    private static final int MUTED = 0xFF9DAEC2;
    private static final int ACCENT = 0xFF52E0C2;
    private static final int ACCENT_DARK = 0xFF173D3C;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Uri photo, video;
    private TextView status, photoLabel, videoLabel, results, resultTitle;
    private ImageView photoPreview;
    private Button choosePhotoButton, clearPhotoButton, chooseVideoButton;
    private Button analyzeButton, exportButton, modelButton, copyButton, shareButton;
    private CheckBox nnapiToggle, decoderToggle;
    private boolean preferAccelerator = true, preferSequentialDecoder = true;
    private SeekBar frameIntervalBar;
    private int frameInterval = 5;
    private List<VideoAnalyzer.Segment> lastSegments = Collections.emptyList();
    private boolean busy = false;
    private String lastReport = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Video Face Search");
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(30));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{BG, 0xFF0D1827, BG}));
        scroll.addView(root);

        root.addView(buildHeader());
        root.addView(buildQuickStart(), cardParams(14));
        root.addView(buildReferenceCard(), cardParams(12));
        root.addView(buildLiveCard(), cardParams(12));
        root.addView(buildVideoCard(), cardParams(12));
        root.addView(buildSettingsCard(), cardParams(12));
        root.addView(buildModelCard(), cardParams(12));
        root.addView(buildResultsCard(), cardParams(12));
        setContentView(scroll);

        // One-time migration of a previously installed converted GPU model.
        if (!new ModelStore(this).ncnnReady()) {
            setBusy(true);
            executor.execute(() -> {
                try { new ModelStore(this).ensureNcnnCached(this::update); }
                catch (Exception e) { update("GPU 权重尚未迁移：" + e.getMessage()); }
                finally { setBusy(false); }
            });
        } else {
            update("GPU 权重已持久保存；轻量更新可以直接复用。");
        }
    }

    private View buildHeader() {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, 0, 0, dp(3));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = overline("FACE / SEARCH  ·  LOCAL AI");
        top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        TextView version = label("V0.9.0", 11, true);
        version.setTextColor(MUTED);
        version.setGravity(Gravity.CENTER);
        version.setBackground(panel(0xFF172637, 30));
        version.setPadding(dp(10), dp(4), dp(10), dp(4));
        top.addView(version, new LinearLayout.LayoutParams(-2, -2));
        block.addView(top);

        TextView title = label("实时人脸检索", 30, true);
        title.setPadding(0, dp(14), 0, dp(2));
        block.addView(title);
        TextView subtitle = label("摄像头实时监测 · 本地视频搜索 · 全程设备端推理", 13, false);
        subtitle.setTextColor(MUTED);
        block.addView(subtitle);

        TextView privacy = label("已授权数据使用 · 不上传人脸 · 候选相似度需要人工核验", 11, false);
        privacy.setTextColor(0xFFB4C2D2);
        privacy.setPadding(0, dp(12), 0, dp(4));
        block.addView(privacy);
        return block;
    }

    private LinearLayout buildQuickStart() {
        LinearLayout card = card();
        card.addView(overline("QUICK START"));
        TextView title = label("先选一张参考人脸", 21, true);
        title.setPadding(0, dp(8), 0, dp(2));
        card.addView(title);
        TextView copy = label("选好照片后，可以直接打开实时摄像头，或对手机里的视频做离线检索。", 13, false);
        copy.setTextColor(MUTED);
        card.addView(copy);

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(14), 0, 0);
        Button live = actionButton("打开实时监测", ACCENT, BG, v -> openLive());
        Button video = actionButton("本地视频搜索", 0xFF22364B, TEXT, v -> focusVideoPicker());
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, dp(50), 1);
        a.rightMargin = dp(8);
        actions.addView(live, a);
        actions.addView(video, new LinearLayout.LayoutParams(0, dp(50), 1));
        card.addView(actions);
        return card;
    }

    private LinearLayout buildReferenceCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("01", "参考人脸"));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        photoPreview = new ImageView(this);
        photoPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photoPreview.setImageResource(android.R.drawable.ic_menu_camera);
        photoPreview.setColorFilter(0xFF9FB4C6, PorterDuff.Mode.SRC_IN);
        photoPreview.setBackground(panel(0xFF1B2A3B, 14));
        row.addView(photoPreview, new LinearLayout.LayoutParams(dp(76), dp(76)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), 0, dp(10), 0);
        photoLabel = label("尚未选择参考照片", 14, true);
        photoLabel.setMaxLines(2);
        info.addView(photoLabel);
        TextView hint = label("建议使用正面、清晰、光线均匀的照片", 12, false);
        hint.setTextColor(MUTED);
        hint.setPadding(0, dp(4), 0, 0);
        info.addView(hint);
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        choosePhotoButton = smallButton("选择", v -> pick("image/*", 101));
        clearPhotoButton = smallButton("清除", v -> clearPhoto());
        buttons.addView(choosePhotoButton, new LinearLayout.LayoutParams(dp(72), dp(38)));
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(dp(72), dp(34));
        clearParams.topMargin = dp(6);
        buttons.addView(clearPhotoButton, clearParams);
        row.addView(buttons);
        card.addView(row);
        return card;
    }

    private LinearLayout buildLiveCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("02", "实时摄像头"));
        TextView copy = label("画面保持在前台，自动调节分析频率。命中时显示红色框、红色边缘闪烁和候选截图。", 13, false);
        copy.setTextColor(MUTED);
        card.addView(copy);
        Button open = actionButton("打开实时监测 · 每 5 帧起步", ACCENT_DARK, ACCENT, v -> openLive());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(50));
        p.topMargin = dp(12);
        card.addView(open, p);
        return card;
    }

    private LinearLayout buildVideoCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("03", "本地视频检索"));
        LinearLayout picker = new LinearLayout(this);
        picker.setGravity(Gravity.CENTER_VERTICAL);
        videoLabel = label("尚未选择视频", 13, false);
        videoLabel.setTextColor(MUTED);
        videoLabel.setMaxLines(2);
        picker.addView(videoLabel, new LinearLayout.LayoutParams(0, -2, 1));
        chooseVideoButton = smallButton("选择视频", v -> pick("video/*", 102));
        picker.addView(chooseVideoButton, new LinearLayout.LayoutParams(dp(96), dp(40)));
        card.addView(picker);

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(12), 0, 0);
        analyzeButton = actionButton("开始搜索", ACCENT, BG, v -> startSearch());
        exportButton = actionButton("导出候选片段", 0xFF22364B, TEXT, v -> exportClips());
        exportButton.setEnabled(false);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, dp(50), 1);
        a.rightMargin = dp(8);
        actions.addView(analyzeButton, a);
        actions.addView(exportButton, new LinearLayout.LayoutParams(0, dp(50), 1));
        card.addView(actions);
        return card;
    }

    private LinearLayout buildSettingsCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("04", "分析设置"));
        nnapiToggle = new CheckBox(this);
        nnapiToggle.setText("Vulkan GPU 优先 · 不可用时回退 CPU");
        nnapiToggle.setTextColor(TEXT);
        nnapiToggle.setTextSize(14);
        nnapiToggle.setChecked(true);
        nnapiToggle.setOnCheckedChangeListener((b, on) -> preferAccelerator = on);
        card.addView(nnapiToggle);
        TextView gpuHint = label("SCRFD 与 ArcFace R50 优先走 ncnn Vulkan；保持原模型和识别阈值。", 12, false);
        gpuHint.setTextColor(MUTED);
        gpuHint.setPadding(dp(6), 0, 0, dp(7));
        card.addView(gpuHint);

        decoderToggle = new CheckBox(this);
        decoderToggle.setText("MediaCodec 顺序解码（推荐）");
        decoderToggle.setTextColor(TEXT);
        decoderToggle.setTextSize(14);
        decoderToggle.setChecked(true);
        decoderToggle.setOnCheckedChangeListener((b, on) -> preferSequentialDecoder = on);
        card.addView(decoderToggle);
        TextView decoderHint = label("只对采样帧送入模型；格式不兼容时自动回退旧解码路径。", 12, false);
        decoderHint.setTextColor(MUTED);
        decoderHint.setPadding(dp(6), 0, 0, dp(5));
        card.addView(decoderHint);

        final TextView intervalText = label("采样间隔  ·  每 5 帧分析一次", 14, true);
        intervalText.setPadding(dp(6), dp(7), 0, 0);
        card.addView(intervalText);
        frameIntervalBar = new SeekBar(this);
        frameIntervalBar.setMax(29);
        frameIntervalBar.setProgress(4);
        frameIntervalBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean user) {
                frameInterval = p + 1;
                intervalText.setText("采样间隔  ·  每 " + frameInterval + " 帧分析一次");
            }
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) {}
        });
        card.addView(frameIntervalBar);
        TextView intervalHint = label("数值越小越密集，速度和发热也会增加；实时摄像头仍使用自适应策略。", 12, false);
        intervalHint.setTextColor(MUTED);
        intervalHint.setPadding(dp(6), 0, 0, 0);
        card.addView(intervalHint);
        return card;
    }

    private LinearLayout buildModelCard() {
        LinearLayout card = card();
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(sectionTitle("05", "模型与设备"), new LinearLayout.LayoutParams(0, -2, 1));
        TextView offline = label("OFFLINE", 10, true);
        offline.setTextColor(ACCENT);
        offline.setPadding(dp(9), dp(4), dp(9), dp(4));
        offline.setBackground(panel(0xFF173D3C, 30));
        heading.addView(offline);
        card.addView(heading);
        status = label("模型状态将在这里显示。首次运行会下载 InsightFace buffalo_l。", 13, false);
        status.setTextColor(0xFFD7E4F0);
        status.setBackground(panel(0xFF172537, 12));
        status.setPadding(dp(12), dp(11), dp(12), dp(11));
        card.addView(status);
        modelButton = actionButton("检查模型 / 迁移 GPU 权重", 0xFF22364B, TEXT, v -> prepareModel());
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, dp(46));
        mp.topMargin = dp(10);
        card.addView(modelButton, mp);
        return card;
    }

    private LinearLayout buildResultsCard() {
        LinearLayout card = card();
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        resultTitle = sectionTitle("06", "分析结果");
        heading.addView(resultTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView local = label("LOCAL", 10, true);
        local.setTextColor(MUTED);
        heading.addView(local);
        card.addView(heading);
        results = label("选择参考照片和视频后，分析报告会出现在这里。", 13, false);
        results.setTextColor(0xFFD7E4F0);
        results.setBackground(panel(0xFF0C1522, 12));
        results.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.addView(results);
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(10), 0, 0);
        copyButton = smallButton("复制报告", v -> copyReport());
        shareButton = smallButton("分享报告", v -> shareReport());
        copyButton.setEnabled(false);
        shareButton.setEnabled(false);
        actions.addView(copyButton, new LinearLayout.LayoutParams(0, dp(40), 1));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(40), 1);
        sp.leftMargin = dp(8);
        actions.addView(shareButton, sp);
        card.addView(actions);
        TextView footer = label("候选时间段只是检索线索，不构成身份确认；请结合现场和授权记录复核。", 11, false);
        footer.setTextColor(MUTED);
        footer.setPadding(dp(4), dp(11), dp(4), 0);
        card.addView(footer);
        return card;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(14), dp(15), dp(15));
        card.setBackground(panel(PANEL, 18));
        return card;
    }

    private LinearLayout.LayoutParams cardParams(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(top);
        return p;
    }

    private TextView sectionTitle(String number, String title) {
        TextView view = label(number + "  /  " + title, 16, true);
        view.setTextColor(TEXT);
        view.setPadding(0, 0, 0, dp(9));
        return view;
    }

    private TextView overline(String text) {
        TextView view = label(text, 11, true);
        view.setTextColor(ACCENT);
        view.setLetterSpacing(.08f);
        return view;
    }

    private TextView label(String text, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(TEXT);
        view.setIncludeFontPadding(true);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    private Button actionButton(String text, int color, int textColor, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(textColor);
        button.setMinHeight(dp(46));
        button.setOnClickListener(listener);
        button.setBackground(panel(color, 12));
        return button;
    }

    private Button smallButton(String text, View.OnClickListener listener) {
        Button button = actionButton(text, 0xFF22364B, TEXT, listener);
        button.setTextSize(13);
        button.setMinHeight(dp(38));
        return button;
    }

    private GradientDrawable panel(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void pick(String mime, int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType(mime);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, requestCode);
    }

    private void focusVideoPicker() {
        if (video == null) pick("video/*", 102);
        else startSearch();
    }

    private void openLive() {
        Intent intent = new Intent(this, LiveCameraActivity.class);
        if (photo != null) intent.putExtra("reference_uri", photo.toString());
        intent.putExtra("use_accelerator", preferAccelerator);
        startActivity(intent);
    }

    @Override protected void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
        if (code != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception ignored) {}
        if (request == 101) {
            photo = uri;
            photoLabel.setText(uri.getLastPathSegment() == null ? "参考照片已选择" : uri.getLastPathSegment());
            renderPhotoPreview(uri);
        } else if (request == 102) {
            video = uri;
            videoLabel.setText(uri.getLastPathSegment() == null ? "本地视频已选择" : uri.getLastPathSegment());
        }
    }

    private void renderPhotoPreview(Uri uri) {
        executor.execute(() -> {
            Bitmap bitmap = null;
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                if (stream != null) bitmap = BitmapFactory.decodeStream(stream);
                Bitmap result = bitmap;
                if (result != null) {
                    int longest = Math.max(result.getWidth(), result.getHeight());
                    if (longest > 320) {
                        float scale = 320f / longest;
                        result = Bitmap.createScaledBitmap(result,
                                Math.round(result.getWidth() * scale), Math.round(result.getHeight() * scale), true);
                        if (result != bitmap) bitmap.recycle();
                    }
                    Bitmap finalResult = result;
                    runOnUiThread(() -> {
                        photoPreview.clearColorFilter();
                        photoPreview.setImageBitmap(finalResult);
                    });
                }
            } catch (Exception ignored) {
                // The full-resolution picker path reports the actionable error when analysis starts.
            }
        });
    }

    private void clearPhoto() {
        if (busy) return;
        photo = null;
        photoLabel.setText("尚未选择参考照片");
        photoPreview.setImageResource(android.R.drawable.ic_menu_camera);
        photoPreview.setColorFilter(0xFF9FB4C6, PorterDuff.Mode.SRC_IN);
    }

    private void update(String text) { update(text, true); }

    private void update(String text, boolean show) {
        runOnUiThread(() -> {
            if (status != null) status.setText(text);
        });
    }

    private void setBusy(boolean enabled) {
        busy = enabled;
        runOnUiThread(() -> {
            if (modelButton != null) modelButton.setEnabled(!enabled);
            if (analyzeButton != null) analyzeButton.setEnabled(!enabled);
            if (choosePhotoButton != null) choosePhotoButton.setEnabled(!enabled);
            if (clearPhotoButton != null) clearPhotoButton.setEnabled(!enabled);
            if (chooseVideoButton != null) chooseVideoButton.setEnabled(!enabled);
            if (frameIntervalBar != null) frameIntervalBar.setEnabled(!enabled);
            if (nnapiToggle != null) nnapiToggle.setEnabled(!enabled);
            if (decoderToggle != null) decoderToggle.setEnabled(!enabled);
            if (copyButton != null) copyButton.setEnabled(!enabled && !lastReport.isEmpty());
            if (shareButton != null) shareButton.setEnabled(!enabled && !lastReport.isEmpty());
            if (exportButton != null) exportButton.setEnabled(!enabled && !lastSegments.isEmpty());
        });
    }

    private void prepareModel() {
        if (busy) return;
        setBusy(true);
        executor.execute(() -> {
            try {
                ModelStore store = new ModelStore(this);
                store.ensure(this::update);
                try { store.ensureNcnnCached(this::update); }
                catch (Exception e) { update("ONNX 模型已就绪；GPU 权重暂未迁移：" + e.getMessage()); }
                update("模型已就绪，可以离线分析。");
            } catch (Exception e) {
                update("模型准备失败：" + e.getMessage());
            } finally { setBusy(false); }
        });
    }

    private Bitmap readPhoto(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("无法打开参考照片");
            Bitmap image = BitmapFactory.decodeStream(in);
            if (image == null) throw new IOException("不是有效图片");
            if (Math.max(image.getWidth(), image.getHeight()) <= 1600) return image;
            float ratio = 1600f / Math.max(image.getWidth(), image.getHeight());
            Bitmap scaled = Bitmap.createScaledBitmap(image, Math.round(image.getWidth() * ratio),
                    Math.round(image.getHeight() * ratio), true);
            image.recycle();
            return scaled;
        }
    }

    private void startSearch() {
        if (busy) return;
        if (photo == null || video == null) {
            new AlertDialog.Builder(this).setTitle("还差一步")
                    .setMessage("请先选择参考照片和本地视频，再开始检索。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        lastSegments = Collections.emptyList();
        lastReport = "";
        results.setText("正在准备模型和视频解码器…");
        copyButton.setEnabled(false);
        shareButton.setEnabled(false);
        setBusy(true);
        executor.execute(() -> {
            try {
                ModelStore store = new ModelStore(this);
                store.ensure(this::update);
                try { store.ensureNcnnCached(this::update); }
                catch (Exception e) { update("Vulkan 权重不可用，将使用兼容模式：" + e.getMessage()); }
                update("加载 InsightFace 检测和识别模型…");
                long jobStart = SystemClock.elapsedRealtime();
                try (FaceEngine engine = new FaceEngine(store.detector(), store.recognizer(), preferAccelerator,
                        getCacheDir(), getAssets())) {
                    long initializationMs = SystemClock.elapsedRealtime() - jobStart;
                    long referenceStart = SystemClock.elapsedRealtime();
                    Bitmap reference = readPhoto(photo);
                    List<FaceEngine.Face> query;
                    try { query = engine.analyze(reference); } finally { reference.recycle(); }
                    if (query.isEmpty()) throw new IllegalArgumentException("参考照片没有检测到可用人脸");
                    query.sort((a, b) -> Float.compare(b.confidence, a.confidence));
                    update("参考人脸已提取，开始视频分析…\n" + engine.providerStatus());
                    long referenceMs = SystemClock.elapsedRealtime() - referenceStart;
                    engine.resetStageTiming();
                    VideoAnalyzer.Report report = new VideoAnalyzer(this).search(engine, video, query.get(0).embedding,
                            frameInterval, 0.48f, preferSequentialDecoder, this::update);
                    lastSegments = report.segments;
                    long profileStart = SystemClock.elapsedRealtime();
                    String profile = engine.finishProfiling();
                    long profileMs = SystemClock.elapsedRealtime() - profileStart;
                    long elapsed = SystemClock.elapsedRealtime() - jobStart;
                    StringBuilder out = new StringBuilder();
                    out.append("总耗时 ").append(elapsed).append(" ms · 视频解码 ").append(report.decodeMs)
                            .append(" ms · 模型推理 ").append(report.inferenceMs).append(" ms。\n");
                    out.append(String.format(Locale.US, "初始化 %.3f 秒 · 参考图 %.3f 秒 · 日志整理 %.3f 秒\n",
                            initializationMs / 1000d, referenceMs / 1000d, profileMs / 1000d));
                    out.append(engine.stageTiming()).append("\n\n");
                    out.append("解码后端：").append(report.decoder).append("\n");
                    if (report.decodedFrames >= 0) out.append("顺序解码输出 ").append(report.decodedFrames)
                            .append(" 帧（仅采样帧送入模型）。\n");
                    out.append(engine.providerStatus()).append("\n").append(profile).append("\n\n");
                    out.append("已扫描 ").append(report.sampledFrames).append(" 帧，检测人脸 ").append(report.faceCount)
                            .append(" 次，超过阈值的帧内候选 ").append(report.rawHits).append(" 个。\n");
                    if (lastSegments.isEmpty()) out.append("没有达到阈值的候选（不代表目标一定未出现）。");
                    for (int i = 0; i < lastSegments.size(); i++) {
                        VideoAnalyzer.Segment segment = lastSegments.get(i);
                        out.append("\n候选 ").append(i + 1).append("：")
                                .append(format(segment.startMs)).append(" – ").append(format(segment.endMs))
                                .append(" · 平均相似度 ")
                                .append(String.format(Locale.US, "%.3f", segment.score))
                                .append(" · ").append(segment.frames).append(" 次匹配");
                    }
                    lastReport = out.toString();
                    runOnUiThread(() -> {
                        results.setText(lastReport);
                        resultTitle.setText(lastSegments.isEmpty() ? "06  /  分析结果 · 未命中" :
                                "06  /  分析结果 · " + lastSegments.size() + " 个候选");
                        resultTitle.setTextColor(lastSegments.isEmpty() ? TEXT : ACCENT);
                        copyButton.setEnabled(true);
                        shareButton.setEnabled(true);
                    });
                    update(lastSegments.isEmpty() ? "扫描完成，没有找到超过阈值的候选。" :
                            "扫描完成，找到 " + lastSegments.size() + " 个候选时间段。");
                }
            } catch (Exception e) {
                update("分析失败：" + e.getClass().getSimpleName() + "：" + e.getMessage());
                runOnUiThread(() -> results.setText("请检查模型、视频格式和剩余存储空间。"));
            } finally { setBusy(false); }
        });
    }

    private static String format(long time) {
        return String.format(Locale.US, "%02d:%02d.%03d", time / 60000, (time / 1000) % 60, time % 1000);
    }

    private void exportClips() {
        if (busy || video == null || lastSegments.isEmpty()) return;
        setBusy(true);
        executor.execute(() -> {
            int saved = 0;
            try {
                for (int i = 0; i < lastSegments.size(); i++) {
                    update("正在导出候选片段 " + (i + 1) + "/" + lastSegments.size());
                    ClipExporter.export(this, video, lastSegments.get(i), i + 1);
                    saved++;
                }
                update("成功导出 " + saved + " 个片段，已保存到应用私有目录。");
            } catch (Exception e) {
                update("导出失败，已成功 " + saved + " 个：" + e.getMessage());
            } finally { setBusy(false); }
        });
    }

    private void copyReport() {
        if (lastReport.isEmpty()) return;
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager != null) manager.setText(lastReport);
        Toast.makeText(this, "报告已复制", Toast.LENGTH_SHORT).show();
    }

    private void shareReport() {
        if (lastReport.isEmpty()) return;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, "Video Face Search 分析报告");
        share.putExtra(Intent.EXTRA_TEXT, lastReport);
        startActivity(Intent.createChooser(share, "分享分析报告"));
    }

    @Override public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
