package cn.zhuchenyu.videofacesearch;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.util.Size;
import android.view.*;
import android.widget.*;

import androidx.activity.ComponentActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Foreground-only CameraX demo. Samples one in five camera frames initially;
 * increases the stride when the per-frame GPU workload exceeds the camera budget.
 *
 * No video recording or remote transmission. Candidate similarity != identity proof.
 */
public final class LiveCameraActivity extends ComponentActivity {
    private static final int PERMISSION_CAMERA=707;
    private static final int PICK_REFERENCE=708;
    private static final int MIN_STRIDE=5, MAX_STRIDE=60;
    private static final float THRESHOLD=0.48f;
    private final ExecutorService cameraExecutor=Executors.newSingleThreadExecutor();
    private final ExecutorService inferenceExecutor=Executors.newSingleThreadExecutor();
    private final AtomicBoolean working=new AtomicBoolean(false);
    private final AtomicInteger generation=new AtomicInteger();
    private final AtomicLong delivered=new AtomicLong();
    private final AtomicLong processed=new AtomicLong();
    private final AtomicLong droppedBusy=new AtomicLong();
    private volatile int stride=MIN_STRIDE;
    private volatile double cameraFps=30.0;
    private volatile double latencyEwmaMs=0;
    private volatile boolean started;
    private volatile boolean loading;
    private volatile int stableSamples;
    private volatile long startedAtMs;
    private long fpsWindowStartNs, fpsWindowFrames;

    private Button startButton,stopButton,chooseButton;
    private PreviewView previewView;
    private FaceOverlayView overlayView;
    private CandidateStore candidateStore;
    private LinearLayout galleryStrip;
    private TextView galleryTitle;
    private final int COLOR_BG=0xFF0C1426, COLOR_PANEL=0xFF18243A, COLOR_TEXT=0xFFF3F7FC, COLOR_MUTED=0xFF94A7BF, COLOR_ACCENT=0xFF22D6AD;
    private TextView status,metrics,matchStatus,refLabel;
    private ProcessCameraProvider cameraProvider;
    private FaceEngine engine;
    private float[] referenceEmbedding;
    private Uri referenceUri;
    private boolean useAccelerator=true;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Face Search · Live");
        getWindow().setStatusBarColor(0xFF0C1426);
        getWindow().setNavigationBarColor(0xFF0C1426);
        candidateStore=new CandidateStore(this);
        useAccelerator=getIntent().getBooleanExtra("use_accelerator",true);
        String uri=getIntent().getStringExtra("reference_uri");
        if(uri!=null)try{referenceUri=Uri.parse(uri);}catch(Exception ignored){}

        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14),dp(16),dp(14),dp(32));
        GradientDrawable background=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xFF0C1426,0xFF101B30,0xFF0E172B});
        root.setBackground(background);scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BG);
        scroll.addView(root);
        TextView brand=label("FACE / SEARCH   ·   LIVE",12,true);
        brand.setTextColor(COLOR_ACCENT);root.addView(brand);
        root.addView(label("实时智能分析",29,true));
        TextView subtitle=label("VULKAN GPU  ·  ADAPTIVE SCAN  ·  ON DEVICE",11,false);
        subtitle.setTextColor(COLOR_MUTED);root.addView(subtitle);
        root.addView(label("仅限已获授权的测试 · 候选相似度不代表身份确认",11,false));

        previewView=new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        FrameLayout cameraFrame=new FrameLayout(this);
        cameraFrame.addView(previewView,new FrameLayout.LayoutParams(-1,dp(400)));
        overlayView=new FaceOverlayView(this);
        cameraFrame.addView(overlayView,new FrameLayout.LayoutParams(-1,dp(400)));
        GradientDrawable cameraBg=new GradientDrawable();
        cameraBg.setColor(0xFF152136);cameraBg.setCornerRadius(dp(18));
        cameraFrame.setBackground(cameraBg);
        cameraFrame.setClipToOutline(true);
        TextView hint=label("● LIVE  ·  CAMERA 01",12,true);
        hint.setTextColor(COLOR_ACCENT);
        hint.setBackgroundColor(0xB20C1726);
        FrameLayout.LayoutParams hintParams=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);
        cameraFrame.addView(hint,hintParams);
        root.addView(cameraFrame);
        TextView legend=label("青色：普通人脸   /   红色：达到匹配阈值（同时闪烁红色边框）",12,false);
        legend.setTextColor(COLOR_MUTED);root.addView(legend);

        TextView referenceTitle=label("01  /  参考人脸",16,true);
        root.addView(referenceTitle);
        chooseButton=button("选择参考照片",v->chooseReference());
        root.addView(chooseButton);
        refLabel=label(referenceUri==null?"尚未选择参考照片":"参考照片已选，启动时读取",13,false);
        root.addView(refLabel);

        LinearLayout controls=new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        startButton=button("开始实时分析",v->start());
        stopButton=button("停止",v->stop());
        controls.setPadding(0,dp(8),0,dp(6));
        LinearLayout.LayoutParams startParams=new LinearLayout.LayoutParams(0,dp(55),1f);
        startParams.rightMargin=dp(8);
        controls.addView(startButton,startParams);
        controls.addView(stopButton,new LinearLayout.LayoutParams(0,dp(55),.65f));
        styleButton(startButton,COLOR_ACCENT,COLOR_BG);
        styleButton(stopButton,0xFF29364D,COLOR_TEXT);
        root.addView(controls);
        stopButton.setEnabled(false);

        root.addView(label("02  /  实时分析状态",16,true));
        status=label("准备就绪。每5帧分析一次，算力不足时智能降低频率。",13,false);
        metrics=label("PREVIEW -- FPS   ·   SCAN -- FPS   ·   /5 FRAMES",14,true);
        matchStatus=label("当前状态 · 尚未开始",15,true);
        LinearLayout dashboard=new LinearLayout(this);
        dashboard.setOrientation(LinearLayout.VERTICAL);
        dashboard.setPadding(dp(12),dp(10),dp(12),dp(10));
        dashboard.setBackground(panelBackground(COLOR_PANEL,14));
        dashboard.addView(metrics);dashboard.addView(matchStatus);dashboard.addView(status);
        root.addView(dashboard);

        root.addView(label("03  /  候选人脸截图",16,true));
        galleryTitle=label("截图仅保存在应用本地，最多40张；每次保留候选脸部裁剪。",12,false);
        galleryTitle.setTextColor(COLOR_MUTED);root.addView(galleryTitle);
        HorizontalScrollView screenshots=new HorizontalScrollView(this);
        screenshots.setHorizontalScrollBarEnabled(false);
        galleryStrip=new LinearLayout(this);galleryStrip.setOrientation(LinearLayout.HORIZONTAL);
        screenshots.addView(galleryStrip);root.addView(screenshots);
        Button clear=button("清空已保存的候选截图",v->new AlertDialog.Builder(this)
            .setTitle("清空候选截图？")
            .setMessage("仅删除本应用私有目录里的候选人脸截图，不影响原视频和模型。")
            .setNegativeButton("取消",null)
            .setPositiveButton("删除",(dialog,which)->{
                candidateStore.clearAll();renderGallery();
            }).show());
        styleButton(clear,0xFF263348,COLOR_MUTED);
        root.addView(clear);
        renderGallery();
        TextView footer=label("仅前台使用 · 不上传人脸 · 不记录连续视频 · 停止即关闭摄像头",11,false);
        footer.setTextColor(COLOR_MUTED);root.addView(footer);
        setContentView(scroll);
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView label(String text,int size,boolean bold) {
        TextView t=new TextView(this);t.setText(text);t.setTextSize(size);
        t.setTextColor(COLOR_TEXT);t.setPadding(dp(5),dp(8),dp(5),dp(8));
        if(bold)t.setTypeface(null,android.graphics.Typeface.BOLD);
        return t;
    }
    private Button button(String name,View.OnClickListener onClick){
        Button b=new Button(this);b.setText(name);b.setAllCaps(false);
        b.setTextSize(14);b.setOnClickListener(onClick);
        styleButton(b,0xFF24344B,COLOR_TEXT);return b;
    }
    private GradientDrawable panelBackground(int color,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;
    }
    private void styleButton(Button b,int color,int text){
        b.setTextColor(text);b.setBackground(panelBackground(color,12));
        b.setMinHeight(dp(48));
    }
    private void renderGallery(){
        if(galleryStrip==null)return;
        galleryStrip.removeAllViews();
        List<File> images=candidateStore.list();
        galleryTitle.setText("已保存 "+images.size()+" 张候选人脸 · 私有存储 · 长按前请人工核验");
        if(images.isEmpty()){
            TextView empty=label("暂无候选截图   ·   找到候选后自动出现在这里",13,false);
            empty.setTextColor(COLOR_MUTED);galleryStrip.addView(empty);return;
        }
        for(File file:images){
            LinearLayout item=new LinearLayout(this);item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(6),dp(6),dp(6),dp(6));item.setBackground(panelBackground(COLOR_PANEL,12));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(114),dp(148));lp.rightMargin=dp(8);
            BitmapFactory.Options opt=new BitmapFactory.Options();opt.inSampleSize=2;
            Bitmap thumb=BitmapFactory.decodeFile(file.getAbsolutePath(),opt);
            ImageView iv=new ImageView(this);iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if(thumb!=null)iv.setImageBitmap(thumb);
            item.addView(iv,new LinearLayout.LayoutParams(-1,dp(104)));
            TextView caption=label(String.format(Locale.US,"相似度 %.3f",CandidateStore.scoreFromName(file)/1000f),12,true);
            caption.setTextColor(COLOR_ACCENT);
            item.addView(caption);galleryStrip.addView(item,lp);
            item.setOnClickListener(v->{
                ImageView zoom=new ImageView(this);zoom.setPadding(dp(8),dp(8),dp(8),dp(8));
                zoom.setAdjustViewBounds(true);
                Bitmap full=BitmapFactory.decodeFile(file.getAbsolutePath());
                if(full!=null)zoom.setImageBitmap(full);
                new AlertDialog.Builder(this).setTitle(file.getName())
                   .setView(zoom)
                   .setPositiveButton("关闭",(d,w)->{if(full!=null)full.recycle();})
                   .show();
            });
        }
    }
    private void update(String s){runOnUiThread(()->status.setText(s));}
    private void chooseReference(){
        if(started||loading)stop();
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("image/*");i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i,PICK_REFERENCE);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==PICK_REFERENCE && result==RESULT_OK && data!=null && data.getData()!=null){
            referenceUri=data.getData();
            try{getContentResolver().takePersistableUriPermission(referenceUri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
            catch(Exception ignored){}
            refLabel.setText("已选参考照片："+referenceUri.getLastPathSegment());
        }
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==PERMISSION_CAMERA && grants.length>0 && grants[0]==PackageManager.PERMISSION_GRANTED)start();
        else if(request==PERMISSION_CAMERA)update("没有摄像头权限，无法运行实时分析。");
    }
    private Bitmap readReference()throws IOException{
        if(referenceUri==null)throw new IOException("请先选择参考照片");
        try(InputStream stream=getContentResolver().openInputStream(referenceUri)){
            if(stream==null)throw new IOException("参考照片无法打开");
            Bitmap bitmap=BitmapFactory.decodeStream(stream);
            if(bitmap==null)throw new IOException("参考照片格式不支持");
            if(Math.max(bitmap.getWidth(),bitmap.getHeight())<=1600)return bitmap;
            float r=1600f/Math.max(bitmap.getWidth(),bitmap.getHeight());
            Bitmap resized=Bitmap.createScaledBitmap(bitmap,Math.round(bitmap.getWidth()*r),Math.round(bitmap.getHeight()*r),true);
            bitmap.recycle();
            return resized;
        }
    }
    private void start(){
        if(started||loading)return;
        if(referenceUri==null){chooseReference();return;}
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.CAMERA},PERMISSION_CAMERA);
            return;
        }
        int run=generation.incrementAndGet();
        loading=true;
        startButton.setEnabled(false);
        chooseButton.setEnabled(false);
        matchStatus.setText("实时候选：初始化模型中...");
        update("加载模型与参考人脸，首次启动可能需要下载原始 buffalo_l 模型...");
        inferenceExecutor.execute(()->{
            FaceEngine created=null;
            try{
                ModelStore models=new ModelStore(this);
                models.ensure(this::update);
                try{models.ensureNcnnCached(this::update);}
                catch(Exception copyError){update("Vulkan 模型持久化失败，将尝试兼容模式："+copyError.getMessage());}
                created=new FaceEngine(models.detector(),models.recognizer(),
                        useAccelerator,getCacheDir(),getAssets());
                Bitmap picture=readReference();
                List<FaceEngine.Face> faces;
                try{faces=created.analyze(picture);}
                finally{picture.recycle();}
                if(faces.isEmpty())throw new IOException("参考图片没有检测到人脸");
                faces.sort((a,b)->Float.compare(b.confidence,a.confidence));
                float[] ref=faces.get(0).embedding;
                if(run!=generation.get())return;
                engine=created;created=null;referenceEmbedding=ref;
                engine.resetStageTiming();
                runOnUiThread(()->{
                    if(run!=generation.get())return;
                    loading=false;started=true;
                    delivered.set(0);processed.set(0);droppedBusy.set(0);
                    stride=MIN_STRIDE;cameraFps=30;latencyEwmaMs=0;stableSamples=0;
                    fpsWindowFrames=0;fpsWindowStartNs=0;
                    startButton.setEnabled(false);stopButton.setEnabled(true);
                    update("模型已加载（持续驻留，避免重复初始化）。开启摄像头...");
                    beginCamera(run);
                });
            }catch(Throwable e){
                if(run==generation.get())runOnUiThread(()->{
                    loading=false;started=false;startButton.setEnabled(true);
                    chooseButton.setEnabled(true);stopButton.setEnabled(false);
                    update("实时分析启动失败："+e.getClass().getSimpleName()+": "+e.getMessage());
                });
            }finally{
                if(created!=null)try{created.close();}catch(Exception ignored){}
            }
        });
    }
    private void beginCamera(int run){
        ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(this);
        future.addListener(()->{
            if(!started||run!=generation.get())return;
            try{
                cameraProvider=future.get();
                cameraProvider.unbindAll();
                Preview preview=new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                ImageAnalysis analysis=new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setTargetResolution(new Size(1280,720))
                    .build();
                analysis.setAnalyzer(cameraExecutor,frame->processCameraFrame(run,frame));
                cameraProvider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,analysis);
                startedAtMs=SystemClock.elapsedRealtime();
                update("摄像头运行中：默认每5帧分析一次，过载时自动降低频率");
            }catch(Exception e){
                update("摄像头启动失败："+e.getMessage());
                stop();
            }
        },ContextCompat.getMainExecutor(this));
    }
    private void processCameraFrame(int run,ImageProxy image){
        Bitmap bitmap=null;
        try{
            if(!started||run!=generation.get())return;
            long count=delivered.incrementAndGet();
            long nowNs=SystemClock.elapsedRealtimeNanos();
            if(fpsWindowStartNs==0){fpsWindowStartNs=nowNs;fpsWindowFrames=0;}
            fpsWindowFrames++;
            long windowNs=nowNs-fpsWindowStartNs;
            if(windowNs>=1_000_000_000L){
                double fps=fpsWindowFrames*1e9/windowNs;
                if(fps>=1&&fps<=120)cameraFps=cameraFps*0.4+fps*0.6;
                fpsWindowStartNs=nowNs;fpsWindowFrames=0;
            }
            if(count%stride!=0)return;
            if(!working.compareAndSet(false,true)){
                droppedBusy.incrementAndGet();
                return;
            }
            bitmap=image.toBitmap();
            int rotation=image.getImageInfo().getRotationDegrees();
            if(rotation!=0){
                Matrix matrix=new Matrix();
                matrix.postRotate(rotation);
                Bitmap corrected=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);
                if(corrected!=bitmap)bitmap.recycle();
                bitmap=corrected;
            }
            int longest=Math.max(bitmap.getWidth(),bitmap.getHeight());
            if(longest>960){
                float scale=960f/longest;
                Bitmap scaled=Bitmap.createScaledBitmap(bitmap,Math.round(bitmap.getWidth()*scale),
                        Math.round(bitmap.getHeight()*scale),true);
                if(scaled!=bitmap)bitmap.recycle();
                bitmap=scaled;
            }
            Bitmap selected=bitmap;
            bitmap=null;
            try{inferenceExecutor.execute(()->inferOne(run,selected));}
            catch(RejectedExecutionException e){selected.recycle();working.set(false);}
        }catch(Throwable error){
            working.set(false);
            if(started&&run==generation.get())update("抽帧异常："+error.getClass().getSimpleName());
        }finally{
            if(bitmap!=null)bitmap.recycle();
            image.close();
        }
    }
    private void inferOne(int run,Bitmap frame){
        long startedNs=SystemClock.elapsedRealtimeNanos();
        try{
            if(!started||run!=generation.get()||engine==null)return;
            List<FaceEngine.Face> faces=engine.analyze(frame);
            float max=-1f;
            int candidates=0;
            for(FaceEngine.Face face:faces) {
                float score=FaceEngine.cosine(referenceEmbedding,face.embedding);
                if(score>max)max=score;
                if(score>=THRESHOLD)candidates++;
            }
            final int n=candidates,visible=faces.size();
            final float best=max;
            processed.incrementAndGet();
            long elapsedMs=(SystemClock.elapsedRealtimeNanos()-startedNs)/1_000_000L;
            adjustStride(elapsedMs);
            long uptime=Math.max(1,SystemClock.elapsedRealtime()-startedAtMs);
            double analyzedFps=processed.get()*1000.0/uptime;
            final String info=String.format(Locale.US,
                "预览: %.1f FPS · 分析: %.2f FPS · 间隔: 每%d帧\\n单帧推理: %d ms · 已分析: %d · 忙时跳过: %d",
                cameraFps,analyzedFps,stride,elapsedMs,processed.get(),droppedBusy.get());
            File saved=null;
            try{if(candidates>0)saved=candidateStore.capture(frame,faces,referenceEmbedding,THRESHOLD);}
            catch(IOException err){update("保存候选截图失败："+err.getMessage());}
            final boolean newScreenshot=saved!=null;
            final int imageW=frame.getWidth(),imageH=frame.getHeight();
            final String match=best<0?"当前采样帧未检测到人脸":
                String.format(Locale.US,"当前采样帧 %d 张脸；最高相似度 %.3f；%s",
                    visible,best,n>0?n+" 个待人工核验候选":"无超过阈值的候选");
            runOnUiThread(()->{
                if(run==generation.get()&&started){
                    metrics.setText(info);
                    matchStatus.setText(n>0?"出现匹配目标 · "+match:match);
                    matchStatus.setTextColor(n>0?0xFFFF5364:COLOR_TEXT);
                    overlayView.show(faces,referenceEmbedding,imageW,imageH,THRESHOLD);
                    if(newScreenshot)renderGallery();
                }
            });
        }catch(Throwable e){
            if(started&&run==generation.get())update("实时分析错误："+e.getClass().getSimpleName()+": "+e.getMessage());
        }finally{frame.recycle();working.set(false);}
    }
    private void adjustStride(long elapsedMs){
        latencyEwmaMs=latencyEwmaMs==0?elapsedMs:latencyEwmaMs*0.65+elapsedMs*0.35;
        double fps=Math.max(10,Math.min(60,cameraFps));
        int required=(int)Math.ceil(latencyEwmaMs*fps*1.25/1000.0);
        required=Math.max(MIN_STRIDE,Math.min(MAX_STRIDE,required));
        if(required>stride){
            stride=required;
            stableSamples=0;
        }else if(required<stride) {
            if(++stableSamples>=8){
                stride=Math.max(MIN_STRIDE,stride-1);
                stableSamples=0;
            }
        }else stableSamples=0;
    }
    private void stop(){
        generation.incrementAndGet();
        started=false;loading=false;
        if(cameraProvider!=null){
            try{cameraProvider.unbindAll();}catch(Exception ignored){}
        }
        stopButton.setEnabled(false);
        startButton.setEnabled(true);
        chooseButton.setEnabled(true);
        overlayView.clear();
        update("已停止实时分析，摄像头已关闭。");
        inferenceExecutor.execute(()->{
            FaceEngine loaded=engine;
            engine=null;referenceEmbedding=null;
            if(loaded!=null)try{loaded.close();}catch(Exception ignored){}
        });
    }
    @Override protected void onStop(){
        super.onStop();
        if(started||loading)stop();
    }
    @Override protected void onDestroy(){
        if(started||loading)stop();
        cameraExecutor.shutdown();
        inferenceExecutor.shutdown();
        super.onDestroy();
    }
}
