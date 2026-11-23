
package cn.zhuchenyu.videofacesearch;

import android.content.Context;
import android.media.*;
import android.net.Uri;
import java.io.*;
import java.nio.ByteBuffer;

public final class ClipExporter {
    public static File export(Context context,Uri source,VideoAnalyzer.Segment segment,int index) throws IOException {
        File dir=new File(context.getExternalFilesDir(null),"segments");
        if(!dir.exists()&&!dir.mkdirs())throw new IOException("Cannot create output folder");
        File file=new File(dir,String.format(java.util.Locale.US,"segment-%02d.mp4",index));
        MediaExtractor extractor=new MediaExtractor();
        MediaMuxer muxer=null;
        boolean started=false;
        try {
            extractor.setDataSource(context,source,null);
            muxer=new MediaMuxer(file.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int tracks=extractor.getTrackCount();
            int[] mapping=new int[tracks];
            java.util.Arrays.fill(mapping,-1);
            int max=1024*1024;
            for(int i=0;i<tracks;i++) {
                MediaFormat fmt=extractor.getTrackFormat(i);
                String mime=fmt.getString(MediaFormat.KEY_MIME);
                if(mime!=null && (mime.startsWith("video/")||mime.startsWith("audio/"))) {
                    mapping[i]=muxer.addTrack(fmt);
                    if(fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                        max=Math.max(max,fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                    extractor.selectTrack(i);
                }
            }
            muxer.start();
            started=true;
            long targetStart=segment.startMs*1000, targetEnd=segment.endMs*1000;
            extractor.seekTo(targetStart,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            ByteBuffer buf=ByteBuffer.allocateDirect(Math.min(max*2,16*1024*1024));
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            long actualStart=-1;
            while(true) {
                int track=extractor.getSampleTrackIndex();
                if(track<0)break;
                long timestamp=extractor.getSampleTime();
                if(timestamp<0 || timestamp>targetEnd)break;
                buf.clear();
                int n=extractor.readSampleData(buf,0);
                if(n<0)break;
                if(mapping[track]>=0) {
                    if(actualStart<0) actualStart=timestamp;
                    info.offset=0; info.size=n;
                    info.presentationTimeUs=Math.max(0,timestamp-actualStart);
                    info.flags=extractor.getSampleFlags();
                    muxer.writeSampleData(mapping[track],buf,info);
                }
                if(!extractor.advance())break;
            }
            return file;
        } finally {
            extractor.release();
            if(muxer!=null) {
                if(started) {try{muxer.stop();}catch(Exception ignored){}}
                muxer.release();
            }
        }
    }
}
