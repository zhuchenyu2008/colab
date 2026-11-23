
package cn.zhuchenyu.videofacesearch;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
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
        Report(List<Segment> s,int n,int f,int h){segments=s;sampledFrames=n;faceCount=f;rawHits=h;}
    }
    private final Context context;
    VideoAnalyzer(Context context) { this.context=context; }
    public Report search(FaceEngine engine, Uri uri,float[] reference, int frameInterval,float threshold,Status callback) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(context,uri);
        ArrayList<Hit> hits=new ArrayList<>();
        int sampled=0, faceCount=0;
        try {
            String d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if(d==null) throw new IllegalArgumentException("视频没有可用时长");
            long duration=Long.parseLong(d);
            float fps=30f;
            String rawFps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            try{ if(rawFps!=null) fps=Float.parseFloat(rawFps); }catch(NumberFormatException ignored){}
            if(fps<1||fps>240)fps=30f;
            long step=Math.max(65,Math.round(frameInterval*1000f/fps));
            for(long t=0;t<duration;t+=step) {
                Bitmap frame=retriever.getFrameAtTime(t*1000L,MediaMetadataRetriever.OPTION_CLOSEST);
                sampled++;
                if(frame==null)continue;
                try{
                    List<FaceEngine.Face> faces=engine.analyze(frame);
                    faceCount+=faces.size();
                    for(FaceEngine.Face face:faces) {
                        float score=FaceEngine.cosine(reference,face.embedding);
                        if(score>=threshold) hits.add(new Hit(t,score));
                    }
                } finally {frame.recycle();}
                if(sampled%3==0 || t+step>=duration)
                    callback.update("视频分析 "+Math.min(100,(100*t/Math.max(1,duration)))+"% · 第 "+sampled+" 帧 · 候选 "+hits.size());
            }
            return new Report(merge(hits,duration),sampled,faceCount,hits.size());
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
