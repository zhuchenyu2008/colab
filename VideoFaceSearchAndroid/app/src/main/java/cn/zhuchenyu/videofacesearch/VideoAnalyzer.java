
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
        public final String decoder;
        public final int decodedFrames;
        Report(List<Segment> s,int n,int f,int h,long decodeMs,long inferenceMs,String decoder,int decodedFrames){
            segments=s;sampledFrames=n;faceCount=f;rawHits=h;
            this.decodeMs=decodeMs; this.inferenceMs=inferenceMs;
            this.decoder=decoder;this.decodedFrames=decodedFrames;
        }
    }
    private final Context context;
    VideoAnalyzer(Context context) { this.context=context; }
    private final class Scan {
        final ArrayList<Hit> hits=new ArrayList<>();
        int sampled=0,faceCount=0;
        long decodeMs=0,inferenceMs=0;
        void consume(FaceEngine engine, Bitmap frame,long t,float[] reference,float threshold,long duration,Status callback)throws Exception{
            sampled++;
            if(frame==null)return;
            long start=SystemClock.elapsedRealtimeNanos();
            try{
                List<FaceEngine.Face> faces=engine.analyze(frame);
                faceCount+=faces.size();
                for(FaceEngine.Face face:faces){
                    float score=FaceEngine.cosine(reference,face.embedding);
                    if(score>=threshold)hits.add(new Hit(t,score));
                }
            }finally{
                inferenceMs+=(SystemClock.elapsedRealtimeNanos()-start)/1_000_000L;
                frame.recycle();
            }
            if(sampled%3==0)callback.update("视频分析 "+Math.min(100,100*t/Math.max(1,duration))+"% · 第 "+sampled+" 帧 · 候选 "+hits.size());
        }
        Report report(long duration,String backend,int decoded){
            return new Report(merge(hits,duration),sampled,faceCount,hits.size(),decodeMs,inferenceMs,backend,decoded);
        }
    }
    public Report search(FaceEngine engine, Uri uri,float[] reference,int frameInterval,float threshold,boolean fastDecode,Status callback)throws Exception{
        MediaMetadataRetriever retriever=new MediaMetadataRetriever();
        try{
            retriever.setDataSource(context,uri);
            String d=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if(d==null)throw new IllegalArgumentException("视频没有可用时长");
            long duration=Long.parseLong(d);
            if(duration<=0)throw new IllegalArgumentException("视频时长无效");
            float fps=30f;
            try{
                String rawFps=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
                if(rawFps!=null)fps=Float.parseFloat(rawFps);
            }catch(Exception ignored){}
            if(!Float.isFinite(fps)||fps<1||fps>240)fps=30f;
            int rotation=0;
            try{rotation=Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));}catch(Exception ignored){}
            long step=Math.max(65,Math.round(frameInterval*1000f/fps));
            Scan scan=new Scan();
            String backend="MediaMetadataRetriever（兼容模式）";
            if(fastDecode){
                SequentialVideoDecoder decoder=null;
                Exception decodeFailure=null;
                long init=SystemClock.elapsedRealtimeNanos();
                try{
                    decoder=new SequentialVideoDecoder(context,uri,step,duration,fps,rotation);
                    backend="MediaCodec 顺序解码 · "+decoder.name();
                    callback.update(backend);
                }catch(Exception e){decodeFailure=e;}
                scan.decodeMs+=(SystemClock.elapsedRealtimeNanos()-init)/1_000_000L;
                if(decoder!=null){
                    try(SequentialVideoDecoder closeable=decoder){
                        while(true){
                            long start=SystemClock.elapsedRealtimeNanos();
                            SequentialVideoDecoder.Frame frame;
                            try{frame=decoder.next();}
                            catch(InterruptedException e){throw e;}
                            catch(Exception e){decodeFailure=e;break;}
                            finally{scan.decodeMs+=(SystemClock.elapsedRealtimeNanos()-start)/1_000_000L;}
                            if(frame==null)break;
                            // Inference failures are not mistaken for video decoder incompatibility.
                            scan.consume(engine,frame.bitmap,frame.timeMs,reference,threshold,duration,callback);
                        }
                        if(decodeFailure==null&&scan.sampled>0)
                            return scan.report(duration,backend,decoder.decodedFrames());
                    }
                }
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                String reason=decodeFailure==null?"未得到采样帧":decodeFailure.getClass().getSimpleName()+": "+decodeFailure.getMessage();
                backend="MediaMetadataRetriever（顺序解码回退："+reason+"）";
                callback.update("顺序解码不可用，重新使用兼容模式："+reason);
                // Restart cleanly; avoid duplicates. Include wasted work in timing statistics.
                long oldDecode=scan.decodeMs,oldInference=scan.inferenceMs;
                scan=new Scan();scan.decodeMs=oldDecode;scan.inferenceMs=oldInference;
            }

            int videoW=0,videoH=0;
            try{
                videoW=Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                videoH=Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                if(rotation==90||rotation==270){int tmp=videoW;videoW=videoH;videoH=tmp;}
            }catch(Exception ignored){}
            float resize=videoW>0&&videoH>0?Math.min(1f,960f/Math.max(videoW,videoH)):1f;
            int desiredW=Math.max(1,Math.round(videoW*resize)),desiredH=Math.max(1,Math.round(videoH*resize));
            for(long t=0;t<duration;t+=step){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                long start=SystemClock.elapsedRealtimeNanos();
                Bitmap frame;
                if(Build.VERSION.SDK_INT>=27&&resize<1f&&videoW>0&&videoH>0){
                    frame=retriever.getScaledFrameAtTime(t*1000L,MediaMetadataRetriever.OPTION_CLOSEST,desiredW,desiredH);
                }else frame=retriever.getFrameAtTime(t*1000L,MediaMetadataRetriever.OPTION_CLOSEST);
                scan.decodeMs+=(SystemClock.elapsedRealtimeNanos()-start)/1_000_000L;
                scan.consume(engine,frame,t,reference,threshold,duration,callback);
            }
            return scan.report(duration,backend,-1);
        }finally{retriever.release();}
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
