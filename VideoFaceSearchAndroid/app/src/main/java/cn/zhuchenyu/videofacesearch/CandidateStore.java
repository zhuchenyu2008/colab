package cn.zhuchenyu.videofacesearch;

import android.content.Context;
import android.graphics.*;
import java.io.*;
import java.util.*;

/** Explicit, private, bounded candidate crop gallery; never stores full camera frames. */
final class CandidateStore {
    static final int MAX_ITEMS=40;
    private final File folder;
    private long lastSavedMs;
    private float[] lastSavedEmbedding;

    CandidateStore(Context context){folder=new File(context.getFilesDir(),"candidate_faces");}
    File folder(){return folder;}
    synchronized File capture(Bitmap frame,List<FaceEngine.Face> people,float[] reference,float threshold) throws IOException{
        long now=System.currentTimeMillis();
        FaceEngine.Face best=null;
        float score=threshold;
        for(FaceEngine.Face f:people){
            float current=FaceEngine.cosine(f.embedding,reference);
            if(current>=score){best=f;score=current;}
        }
        if(best==null)return null;
        if(now-lastSavedMs<2500)return null;
        // Avoid saving the same person's nearly identical face on every sampled frame.
        if(lastSavedEmbedding!=null && FaceEngine.cosine(lastSavedEmbedding,best.embedding)>0.90f
                && now-lastSavedMs<8000)return null;
        float[] box=best.bounds;
        if(box.length<4)return null;
        float w=Math.max(1,box[2]-box[0]),h=Math.max(1,box[3]-box[1]);
        float pad=Math.max(w,h)*0.16f;
        int x=(int)Math.max(0,Math.floor(box[0]-pad));
        int y=(int)Math.max(0,Math.floor(box[1]-pad));
        int right=(int)Math.min(frame.getWidth(),Math.ceil(box[2]+pad));
        int bottom=(int)Math.min(frame.getHeight(),Math.ceil(box[3]+pad));
        if(right<=x||bottom<=y||right-x<32||bottom-y<32)return null;
        if(!folder.exists()&&!folder.mkdirs())throw new IOException("无法建立候选截图目录");
        File target=new File(folder,String.format(Locale.US,"hit-%d-%03d.jpg",
                now,Math.round(score*1000)));
        Bitmap crop=Bitmap.createBitmap(frame,x,y,right-x,bottom-y);
        try(OutputStream stream=new BufferedOutputStream(new FileOutputStream(target))){
            if(!crop.compress(Bitmap.CompressFormat.JPEG,88,stream))
                throw new IOException("JPEG 截图编码失败");
        }catch(IOException e){target.delete();throw e;}
        finally{crop.recycle();}
        lastSavedMs=now;
        lastSavedEmbedding=Arrays.copyOf(best.embedding,best.embedding.length);
        trim();
        return target;
    }
    synchronized List<File> list(){
        if(!folder.exists())return Collections.emptyList();
        File[] files=folder.listFiles((dir,name)->name.startsWith("hit-")&&name.endsWith(".jpg"));
        if(files==null)return Collections.emptyList();
        Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
        return Arrays.asList(files);
    }
    synchronized void clearAll(){
        for(File f:list())f.delete();
        lastSavedEmbedding=null;
        lastSavedMs=0;
    }
    private void trim(){
        List<File> files=list();
        for(int i=MAX_ITEMS;i<files.size();i++)files.get(i).delete();
    }
    static int scoreFromName(File file){
        String s=file.getName();
        int split=s.lastIndexOf('-');
        if(split<0)return 0;
        try{return Integer.parseInt(s.substring(split+1,s.length()-4));}
        catch(NumberFormatException ignored){return 0;}
    }
}
