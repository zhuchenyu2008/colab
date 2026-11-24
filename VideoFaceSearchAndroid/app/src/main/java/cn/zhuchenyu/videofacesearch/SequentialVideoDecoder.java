package cn.zhuchenyu.videofacesearch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Sequential, bounded-memory decoder for ordinary SDR video. No repeated seeking. */
final class SequentialVideoDecoder implements AutoCloseable {
    static final class Frame {
        final Bitmap bitmap;
        final long timeMs;
        Frame(Bitmap bitmap,long timeMs){this.bitmap=bitmap;this.timeMs=timeMs;}
    }

    private final MediaExtractor extractor = new MediaExtractor();
    private MediaCodec codec;
    private MediaFormat colorFormat;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private boolean started, inputEos, outputEos;
    private final long stepUs, toleranceUs, durationUs;
    private final int rotation;
    private long nextSampleUs=0, originUs=Long.MIN_VALUE;
    private int decodedFrames;
    private String name;

    SequentialVideoDecoder(Context ctx, Uri uri,long stepMs,long durationMs,float fps,int rotation) throws Exception {
        stepUs=stepMs*1000L;
        durationUs=durationMs*1000L;
        toleranceUs=Math.min(stepUs/2,Math.round(500000f/Math.max(1f,fps)));
        this.rotation=rotation;
        try {
            extractor.setDataSource(ctx,uri,null);
            int index=-1;
            MediaFormat format=null;
            for(int i=0;i<extractor.getTrackCount();i++){
                MediaFormat f=extractor.getTrackFormat(i);
                String mime=f.getString(MediaFormat.KEY_MIME);
                if(mime!=null && mime.startsWith("video/")){index=i;format=f;break;}
            }
            if(index<0||format==null)throw new IOException("无视频轨道");
            checkSdr(format);
            extractor.selectTrack(index);
            colorFormat=format;
            // Byte-buffer output is not rotated by a surface; perform metadata rotation ourselves.
            format.setInteger(MediaFormat.KEY_ROTATION,0);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            String mime=format.getString(MediaFormat.KEY_MIME);
            codec=MediaCodec.createDecoderByType(mime);
            name=codec.getName();
            if(Build.VERSION.SDK_INT>=29){
                name += codec.getCodecInfo().isHardwareAccelerated()?"（硬件）":"（非硬件或软件）";
            }
            codec.configure(format,null,null,0);
            codec.start();
            started=true;
        }catch(Exception e){close();throw e;}
    }

    String name(){return name;}
    int decodedFrames(){return decodedFrames;}
    private static int integer(MediaFormat f,String key,int fallback){
        try{return f.containsKey(key)?f.getInteger(key):fallback;}catch(Exception e){return fallback;}
    }
    private static void checkSdr(MediaFormat f)throws IOException{
        int transfer=integer(f,MediaFormat.KEY_COLOR_TRANSFER,0);
        if(transfer==MediaFormat.COLOR_TRANSFER_ST2084 || transfer==MediaFormat.COLOR_TRANSFER_HLG)
            throw new IOException("HDR 视频使用兼容解码");
    }
    Frame next()throws Exception{
        long lastProgress=SystemClock.elapsedRealtime();
        while(!outputEos && nextSampleUs<durationUs){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            if(!inputEos){
                int in=codec.dequeueInputBuffer(1000);
                if(in>=0){
                    ByteBuffer buffer=codec.getInputBuffer(in);
                    if(buffer==null)throw new IOException("解码输入缓冲区不可用");
                    buffer.clear();
                    int n=extractor.readSampleData(buffer,0);
                    if(n<0){
                        codec.queueInputBuffer(in,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputEos=true;
                    }else{
                        long pts=extractor.getSampleTime();
                        codec.queueInputBuffer(in,0,n,pts,0);
                        extractor.advance();
                    }
                    lastProgress=SystemClock.elapsedRealtime();
                }
            }
            int out=codec.dequeueOutputBuffer(info,5000);
            if(out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                MediaFormat f=codec.getOutputFormat();
                checkSdr(f);
                // Keep container color metadata if the output omits it.
                if(!f.containsKey(MediaFormat.KEY_COLOR_STANDARD) && colorFormat.containsKey(MediaFormat.KEY_COLOR_STANDARD))
                    f.setInteger(MediaFormat.KEY_COLOR_STANDARD,integer(colorFormat,MediaFormat.KEY_COLOR_STANDARD,0));
                if(!f.containsKey(MediaFormat.KEY_COLOR_RANGE) && colorFormat.containsKey(MediaFormat.KEY_COLOR_RANGE))
                    f.setInteger(MediaFormat.KEY_COLOR_RANGE,integer(colorFormat,MediaFormat.KEY_COLOR_RANGE,0));
                colorFormat=f;
                lastProgress=SystemClock.elapsedRealtime();
                continue;
            }
            if(out>=0){
                lastProgress=SystemClock.elapsedRealtime();
                Frame chosen=null;
                try {
                    outputEos=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    if(info.size>0 && (info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){
                        decodedFrames++;
                        if(originUs==Long.MIN_VALUE)originUs=Math.min(0,info.presentationTimeUs);
                        long pts=Math.max(0,info.presentationTimeUs-originUs);
                        if(pts+toleranceUs>=nextSampleUs){
                            Image image=codec.getOutputImage(out);
                            if(image==null)throw new IOException("解码器不支持可读 YUV 输出");
                            Bitmap bmp;
                            try{bmp=toBitmap(image,colorFormat,rotation);}
                            finally{image.close();}
                            // Analyze at most once per actual decoded frame. Use actual PTS.
                            while(nextSampleUs<=pts+toleranceUs)nextSampleUs+=stepUs;
                            chosen=new Frame(bmp,pts/1000L);
                        }
                    }
                }finally{codec.releaseOutputBuffer(out,false);}
                if(chosen!=null)return chosen;
            }
            if(SystemClock.elapsedRealtime()-lastProgress>12000L)
                throw new IOException("顺序解码器超时");
        }
        if(decodedFrames==0)throw new IOException("顺序解码未输出图像");
        return null;
    }

    /** Honors YUV crop, row/pixel strides, range, and common SDR color matrices. */
    private static Bitmap toBitmap(Image image,MediaFormat f,int rotation)throws IOException{
        if(image.getFormat()!=ImageFormat.YUV_420_888)throw new IOException("非 8-bit YUV420 输出");
        Image.Plane[] planes=image.getPlanes();
        if(planes.length!=3)throw new IOException("不支持的图像平面数量");
        Rect crop=image.getCropRect();
        int w=crop.width(),h=crop.height();
        if(w<=0||h<=0)throw new IOException("无效解码图像区域");
        float ratio=Math.min(1f,960f/Math.max(w,h));
        int dw=Math.max(1,Math.round(w*ratio)),dh=Math.max(1,Math.round(h*ratio));
        int[] argb=new int[dw*dh];
        ByteBuffer yb=planes[0].getBuffer(),ub=planes[1].getBuffer(),vb=planes[2].getBuffer();
        int ybase=yb.position(),ubase=ub.position(),vbase=vb.position();
        int yr=planes[0].getRowStride(),ur=planes[1].getRowStride(),vr=planes[2].getRowStride();
        int yp=planes[0].getPixelStride(),up=planes[1].getPixelStride(),vp=planes[2].getPixelStride();

        int range=integer(f,MediaFormat.KEY_COLOR_RANGE,MediaFormat.COLOR_RANGE_LIMITED);
        int standard=integer(f,MediaFormat.KEY_COLOR_STANDARD,h>=720?MediaFormat.COLOR_STANDARD_BT709:MediaFormat.COLOR_STANDARD_BT601_NTSC);
        boolean full=range==MediaFormat.COLOR_RANGE_FULL;
        boolean bt709=standard==MediaFormat.COLOR_STANDARD_BT709;
        boolean bt2020=standard==MediaFormat.COLOR_STANDARD_BT2020;
        // 8-bit fixed point color conversion.
        int cy=full?256:298, yOffset=full?0:16;
        int rv=full?(bt2020?377:(bt709?403:359)):(bt2020?430:(bt709?459:409));
        int gu=full?(bt2020?42:(bt709?48:88)):(bt2020?48:(bt709?55:100));
        int gv=full?(bt2020?146:(bt709?120:183)):(bt2020?167:(bt709?136:208));
        int bu=full?(bt2020?482:(bt709?475:454)):(bt2020?548:(bt709?541:516));

        int[] x0=new int[dw],x1=new int[dw],xf=new int[dw],xc=new int[dw];
        for(int x=0;x<dw;x++){
            double sx=Math.max(0,Math.min(w-1,(x+.5)*w/dw-.5));
            int a=(int)sx;
            x0[x]=(crop.left+a)*yp;
            x1[x]=(crop.left+Math.min(w-1,a+1))*yp;
            xf[x]=(int)Math.round((sx-a)*256);
            xc[x]=(crop.left+(int)Math.round(sx))/2;
        }
        for(int y=0;y<dh;y++){
            double sy=Math.max(0,Math.min(h-1,(y+.5)*h/dh-.5));
            int a=(int)sy, fy=(int)Math.round((sy-a)*256);
            int yrow0=ybase+(crop.top+a)*yr,yrow1=ybase+(crop.top+Math.min(h-1,a+1))*yr;
            int chromaY=(crop.top+(int)Math.round(sy))/2;
            int urow=ubase+chromaY*ur,vrow=vbase+chromaY*vr;
            for(int x=0;x<dw;x++){
                int fx=xf[x];
                int ya=(yb.get(yrow0+x0[x])&255)*(256-fx)+(yb.get(yrow0+x1[x])&255)*fx;
                int yd=(yb.get(yrow1+x0[x])&255)*(256-fx)+(yb.get(yrow1+x1[x])&255)*fx;
                int luma=(ya*(256-fy)+yd*fy+32768)>>16;
                int u=(ub.get(urow+xc[x]*up)&255)-128;
                int v=(vb.get(vrow+xc[x]*vp)&255)-128;
                int yy=cy*(luma-yOffset);
                int r=clamp((yy+rv*v+128)>>8),g=clamp((yy-gu*u-gv*v+128)>>8),b=clamp((yy+bu*u+128)>>8);
                argb[y*dw+x]=0xff000000|(r<<16)|(g<<8)|b;
            }
        }
        Bitmap bmp=Bitmap.createBitmap(argb,dw,dh,Bitmap.Config.ARGB_8888);
        if(rotation%360!=0){
            Matrix m=new Matrix();m.postRotate(rotation);
            Bitmap rotated=Bitmap.createBitmap(bmp,0,0,dw,dh,m,true);
            if(rotated!=bmp)bmp.recycle();
            bmp=rotated;
        }
        return bmp;
    }

    private static int clamp(int v){return Math.max(0,Math.min(255,v));}
    @Override public void close(){
        if(codec!=null){
            if(started)try{codec.stop();}catch(Exception ignored){}
            try{codec.release();}catch(Exception ignored){}
            codec=null;
        }
        try{extractor.release();}catch(Exception ignored){}
    }
}
