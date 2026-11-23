
package cn.zhuchenyu.videofacesearch;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import java.util.*;

public final class VideoAnalyzer {
    public interface Status { void update(String message); }
    public static final class Hit {
        public final long timeMs; public final float similarity;
        Hit(long t,float s){timeMs=t;similarity=s;}
    }
    public static final class Segment {
        public final long startMs,endMs; public final float score;
        public final int frames;
        Segment(long s,long e,float c,int n){startMs=s;endMs=e;score=c;frames=n;}
    }
    public static final class Report {
        public final List<Segment> segments;
        public final int sampledFrames,faceCount,rawHits;
        public final long decodeMs,inferenceMs;
        Report(List<Segment> s,int n,int f,int h,long decodeMs,long inferenceMs){
            segments=s;sampledFrames=n;faceCount=f;rawHits=h;
            this.decodeMs=decodeMs; this.inferenceMs=inferenceMs;
        }
    }
    private final Context context;
    VideoAnalyzer(Context context) { this.context=context; }
    public Report search(FaceEngine engine, Uri uri,float[] reference, int frameInterval,float threshold,Status callback) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(context,uri);
        ArrayList<Hit> hits=new ArrayList<>();
        int sampled=0, faceCount=0;
        long decodeMs=0, inferenceMs=0;
        try {
            String d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if(d==null) throw new IllegalArgumentException("视频没有可用时长");
            long duration=Long.parseLong(d);
            float fps=30f;
            String rawFps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            try{ if(rawFps!=null) fps=Float.parseFloat(rawFps); }catch(NumberFormatException ignored){}
            if(fps<1||fps>240)fps=30f;
            long step=Math.max(65,Math.round(frameInterval*1000f/fps));
            // The network detector itself works at 640x640. Avoid decoding huge 4K
            // bitmaps into Java memory only to downscale immediately afterwards.
            int videoW=0,videoH=0;
            try{
                videoW=Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                videoH=Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                String rotation=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if("90".equals(rotation)||"270".equals(rotation)){int tmp=videoW;videoW=videoH;videoH=tmp;}
            }catch(Exception ignored){}
            float resize = videoW>0 && videoH>0 ? Math.min(1f,960f/Math.max(videoW,videoH)) : 1f;
            int desiredW=Math.max(1,Math.round(videoW*resize));
            int desiredH=Math.max(1,Math.round(videoH*resize));
            for(long t=0;t<duration;t+=step) {
                long t0=SystemClock.elapsedRealtimeNanos();
                Bitmap frame;
                if (Build.VERSION.SDK_INT>=27 && resize<1f && videoW>0 && videoH>0) {
                    frame=retriever.getScaledFrameAtTime(t*1000L,MediaMetadataRetriever.OPTION_CLOSEST,desiredW,desiredH);
                } else frame=retriever.getFrameAtTime(t*1000L,MediaMetadataRetriever.OPTION_CLOSEST);
                decodeMs += (SystemClock.elapsedRealtimeNanos()-t0)/1_000_000L;
                sampled++;
                if(frame==null)continue;
                try{
                    long begin=SystemClock.elapsedRealtimeNanos();
                    List<FaceEngine.Face> faces=engine.analyze(frame);
                    inferenceMs += (SystemClock.elapsedRealtimeNanos()-begin)/1_000_000L;
                    faceCount+=faces.size();
                    for(FaceEngine.Face face:faces) {
                        float score=FaceEngine.cosine(reference,face.embedding);
                        if(score>=threshold) hits.add(new Hit(t,score));
                    }
                } finally {frame.recycle();}
                if(sampled%3==0 || t+step>=duration)
                    callback.update("视频分析 "+Math.min(100,(100*t/Math.max(1,duration)))+"% · 第 "+sampled+" 帧 · 候选 "+hits.size());
            }
            return new Report(merge(hits,duration),sampled,faceCount,hits.size(),decodeMs,inferenceMs);
        } finally {retriever.release();}
    }
    private List<Segment> merge(List<Hit> hits,long duration) {
        if(hits.isEmpty())return Collections.emptyList();
        hits.sort(Comparator.comparingLong(x->x.timeMs));
        List<Segment> segments = new ArrayList<>();
        long start=hits.get(0).timeMs,end=start;
        float sum=hits.get(0).similarity;
        int count=1;
        for(int i=1;i<hits.size();i++){
            Hit h=hits.get(i);
            if(h.timeMs-end<=2000){
                end=h.timeMs;sum+=h.similarity;count++;
            }else{
                segments.add(new Segment(Math.max(0,start-1000),Math.min(duration,end+1000),sum/count,count));
                start=h.timeMs;end=start;sum=h.similarity;count=1;
            }
        }
        segments.add(new Segment(Math.max(0,start-1000),Math.min(duration,end+1000),sum/count,count));
        return segments;
    }
}
