
package cn.zhuchenyu.videofacesearch;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private Uri photo,video;
    private TextView status,photoLabel,videoLabel,results;
    private Button analyzeButton, exportButton, modelButton;
    private CheckBox nnapiToggle;
    private boolean preferAccelerator = true;
    private SeekBar frameIntervalBar;
    private int frameInterval=5;
    private List<VideoAnalyzer.Segment> lastSegments=Collections.emptyList();
    private boolean busy=false;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        setTitle("Video Face Search");
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(24),dp(20),dp(30));
        scroll.addView(root);
        TextView title=label("Video Face Search",26,true); root.addView(title);
        root.addView(label("DolphinBit/video-face-search 的 Android 原生移植 · 本机离线推理",13,false));
        root.addView(label("用于获得授权的视频素材。匹配属于候选提示，不构成身份确认。",13,false));
        root.addView(label("① 选择参考人脸",18,true));
        root.addView(button("选择参考照片",v->pick("image/*",101)));
        photoLabel=label("尚未选择",14,false);root.addView(photoLabel);
        root.addView(label("② 选择 10–30 秒视频",18,true));
        root.addView(button("选择本地视频",v->pick("video/*",102)));
        videoLabel=label("尚未选择",14,false);root.addView(videoLabel);
        root.addView(label("③ 扫描参数",18,true));
        nnapiToggle = new CheckBox(this);
        nnapiToggle.setText("Vulkan GPU 检测优先（不可用时回退 CPU）");
        nnapiToggle.setChecked(true);
        nnapiToggle.setOnCheckedChangeListener((b,on)->preferAccelerator=on);
        root.addView(nnapiToggle);
        root.addView(label("优先使用 ncnn Vulkan SCRFD 人脸检测，ArcFace 使用 ONNX Runtime；不可用时回退旧版模式。不是全流程 GPU。",12,false));
        final TextView intervalText=label("每 5 帧分析一次（默认沿用原项目）",14,false);
        root.addView(intervalText);
        frameIntervalBar=new SeekBar(this);frameIntervalBar.setMax(29);frameIntervalBar.setProgress(4);
        frameIntervalBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int p,boolean user){frameInterval=p+1;intervalText.setText("每 "+frameInterval+" 帧分析一次（越小越慢、漏检机会越少）");}
            public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){}
        });root.addView(frameIntervalBar);
        modelButton=button("首次下载模型 / 检查模型",v->prepareModel());root.addView(modelButton);
        analyzeButton=button("开始搜索视频",v->startSearch());root.addView(analyzeButton);
        exportButton=button("导出候选视频片段",v->exportClips());exportButton.setEnabled(false);root.addView(exportButton);
        status=label("就绪。首次使用需下载约 200MB 的 buffalo_l 模型，之后可离线运行。",14,false);
        root.addView(status);
        results=label("分析结果将在此显示。",15,false);root.addView(results);
        setContentView(scroll);
    }
    private TextView label(String text,int size,boolean bold) {
        TextView view=new TextView(this);view.setText(text);view.setTextSize(size);
        view.setTextColor(Color.rgb(29,35,43));
        view.setPadding(0,dp(7),0,dp(7));
        if(bold)view.setTypeface(null,Typeface.BOLD);
        return view;
    }
    private Button button(String text,View.OnClickListener l) {
        Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setOnClickListener(l);return b;
    }
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private void pick(String mime,int requestCode) {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType(mime);
        i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,requestCode);
    }
    @Override protected void onActivityResult(int request,int code,Intent data){
        super.onActivityResult(request,code,data);
        if(code!=RESULT_OK || data==null || data.getData()==null)return;
        Uri uri=data.getData();
        try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
        if(request==101){photo=uri;photoLabel.setText(uri.getLastPathSegment());}
        else if(request==102){video=uri;videoLabel.setText(uri.getLastPathSegment());}
    }
    private void update(String s){runOnUiThread(()->status.setText(s));}
    private void setBusy(boolean enabled) {
        busy=enabled;
        runOnUiThread(()->{
            modelButton.setEnabled(!enabled);analyzeButton.setEnabled(!enabled);
            frameIntervalBar.setEnabled(!enabled);
            nnapiToggle.setEnabled(!enabled);
            exportButton.setEnabled(!enabled && !lastSegments.isEmpty());
        });
    }
    private void prepareModel(){
        if(busy)return;
        setBusy(true);
        executor.execute(()->{
            try{
                new ModelStore(this).ensure(this::update);
                update("模型已就绪，之后可离线识别。");
            }catch(Exception e){update("模型准备失败：" + e.getMessage());}
            finally{setBusy(false);}
        });
    }
    private Bitmap readPhoto(Uri uri)throws Exception {
        try(InputStream in=getContentResolver().openInputStream(uri)){
            if(in==null)throw new IOException("无法打开参考照片");
            Bitmap image=BitmapFactory.decodeStream(in);
            if(image==null)throw new IOException("不是有效图片");
            if(Math.max(image.getWidth(),image.getHeight())<=1600)return image;
            float ratio=1600f/Math.max(image.getWidth(),image.getHeight());
            Bitmap scaled=Bitmap.createScaledBitmap(image,Math.round(image.getWidth()*ratio),Math.round(image.getHeight()*ratio),true);
            image.recycle();
            return scaled;
        }
    }
    private void startSearch(){
        if(busy)return;
        if(photo==null||video==null){new AlertDialog.Builder(this).setMessage("请先选择参考照片和视频").setPositiveButton("确定",null).show();return;}
        lastSegments=Collections.emptyList();
        results.setText("正在分析...");
        setBusy(true);
        executor.execute(()->{
            try {
                ModelStore store=new ModelStore(this);store.ensure(this::update);
                update("加载 InsightFace 检测和识别模型...");
                long jobStart = android.os.SystemClock.elapsedRealtime();
                try(FaceEngine engine=new FaceEngine(store.detector(),store.recognizer(),preferAccelerator,getCacheDir(),getAssets())){
                    Bitmap reference=readPhoto(photo);
                    List<FaceEngine.Face> q;
                    try { q=engine.analyze(reference); }finally{reference.recycle();}
                    if(q.isEmpty())throw new IllegalArgumentException("参考照片没有检测到可用人脸");
                    q.sort((a,b)->Float.compare(b.confidence,a.confidence));
                    update("参考人脸已提取，开始视频分析...\n"+engine.providerStatus());
                    VideoAnalyzer.Report report=new VideoAnalyzer(this).search(engine,video,q.get(0).embedding,
                            frameInterval,0.48f,this::update);
                    lastSegments=report.segments;
                    String profile=engine.finishProfiling();
                    long elapsed=android.os.SystemClock.elapsedRealtime()-jobStart;
                    StringBuilder out=new StringBuilder();
                    out.append("总耗时 ").append(elapsed).append(" ms · 视频解码 ")
                       .append(report.decodeMs).append(" ms · 模型推理 ")
                       .append(report.inferenceMs).append(" ms。\n");
                    out.append(engine.providerStatus()).append("\n").append(profile).append("\n\n");
                    out.append("已扫描 ").append(report.sampledFrames).append(" 帧，检测人脸 ").append(report.faceCount)
                            .append(" 次，超过阈值的帧内候选 ").append(report.rawHits).append(" 个。\n");
                    if(lastSegments.isEmpty())out.append("没有达到阈值的候选（不代表目标一定未出现）。");
                    for(int i=0;i<lastSegments.size();i++){
                        VideoAnalyzer.Segment s=lastSegments.get(i);
                        out.append("\n候选 ").append(i+1).append("：")
                            .append(format(s.startMs)).append(" – ").append(format(s.endMs))
                            .append(" · 平均相似度 ").append(String.format(Locale.US,"%.3f",s.score))
                            .append(" · ").append(s.frames).append(" 次匹配");
                    }
                    runOnUiThread(()->results.setText(out.toString()));
                    update("扫描完成，找到 "+lastSegments.size()+" 个候选时间段。");
                }
            } catch(Exception e) {
                update("分析失败："+e.getClass().getSimpleName()+"："+e.getMessage());
                runOnUiThread(()->results.setText("请检查模型、视频和剩余存储空间。"));
            } finally{setBusy(false);}
        });
    }
    private static String format(long t){return String.format(Locale.US,"%02d:%02d.%03d",t/60000,(t/1000)%60,t%1000);}
    private void exportClips() {
        if(busy || video==null || lastSegments.isEmpty())return;
        setBusy(true);
        executor.execute(()->{
            int saved=0;
            try {
                for(int i=0;i<lastSegments.size();i++){
                    update("导出视频 "+(i+1)+"/"+lastSegments.size());
                    ClipExporter.export(this,video,lastSegments.get(i),i+1);saved++;
                }
                update("成功导出 "+saved+" 个片段，保存在 Android/data/cn.zhuchenyu.videofacesearch/files/segments");
            }catch(Exception e){update("导出失败，已成功 "+saved+" 个："+e.getMessage());}
            finally{setBusy(false);}
        });
    }
    @Override public void onDestroy(){super.onDestroy();executor.shutdownNow();}
}
