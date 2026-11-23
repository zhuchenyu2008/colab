
package cn.zhuchenyu.videofacesearch;

import android.graphics.*;
import android.os.Build;
import ai.onnxruntime.providers.NNAPIFlags;
import java.io.*;
import java.io.File;
import java.nio.*;
import java.util.*;
import ai.onnxruntime.*;

public final class FaceEngine implements AutoCloseable {
    public static final class Face {
        public final float[] bounds;
        public final float[] embedding;
        public final float confidence;
        Face(float[] bounds, float[] embedding, float confidence) {
            this.bounds = bounds; this.embedding = embedding; this.confidence = confidence;
        }
    }
    private static final int DET = 640, REC = 112;
    private static final float[][] TEMPLATE = {
        {38.2946f,51.6963f},{73.5318f,51.5014f},{56.0252f,71.7366f},
        {41.5493f,92.3655f},{70.7299f,92.2041f}
    };
    private final OrtEnvironment env;
    private final OrtSession det, rec;
    private final String detInput, recInput;
    private final boolean detNnapi, recNnapi;
    private final String[] providerNotes = new String[2];
    private boolean profileFinished = false;
    private String profileStatus = "未读取推理性能记录";
    private final File profileDir;

    private static final class Prepared {
        final OrtSession session; final boolean nnapi;
        Prepared(OrtSession session, boolean nnapi) { this.session = session; this.nnapi = nnapi; }
    }

    FaceEngine(File detector, File recognizer, boolean preferAccelerator, File cacheDir) throws Exception {
        env = OrtEnvironment.getEnvironment();
        profileDir = new File(cacheDir, "onnx-profiler");
        if (!profileDir.exists()) profileDir.mkdirs();
        Prepared d = openSession(detector, preferAccelerator, "detector", 0);
        det = d.session;
        detNnapi = d.nnapi;
        Prepared r;
        try {
            r = openSession(recognizer, preferAccelerator, "recognizer", 1);
        } catch (Exception e) {
            det.close();
            throw e;
        }
        rec = r.session;
        recNnapi = r.nnapi;
        detInput = det.getInputNames().iterator().next();
        recInput = rec.getInputNames().iterator().next();
    }

    private Prepared openSession(File model, boolean preferAccelerator, String tag, int index) throws Exception {
        // NNAPI dispatches eligible ops to Android device accelerators (GPU/NPU).
        // Explicitly forbid nnapi-reference CPU, keeping unsupported ops on ORT CPU.
        if (preferAccelerator && Build.VERSION.SDK_INT >= 29) {
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setIntraOpNumThreads(2);
                options.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED));
                enableProfile(options, tag);
                OrtSession result = env.createSession(model.getAbsolutePath(), options);
                providerNotes[index] = tag + ": NNAPI 已注册（实际硬件节点将在分析后确认）";
                return new Prepared(result, true);
            } catch (Exception e) {
                providerNotes[index] = tag + ": NNAPI 初始化失败，已回退 CPU: " + e.getClass().getSimpleName();
            }
        } else if (preferAccelerator) {
            providerNotes[index] = tag + ": 安卓版本不支持当前加速配置，回退 CPU";
        } else {
            providerNotes[index] = tag + ": 用户选择 CPU";
        }
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(Math.min(4, Runtime.getRuntime().availableProcessors()));
            enableProfile(options, tag);
            return new Prepared(env.createSession(model.getAbsolutePath(), options), false);
        }
    }

    private void enableProfile(OrtSession.SessionOptions options, String tag) {
        try {
            options.enableProfiling(new File(profileDir, tag + "-" + System.currentTimeMillis()).getAbsolutePath());
        } catch (Exception ignored) { /* inference remains usable even if profiling is unavailable */ }
    }

    public String providerStatus() {
        return providerNotes[0] + "\n" + providerNotes[1];
    }

    public String finishProfiling() {
        if (profileFinished) return profileStatus;
        profileFinished = true;
        int accelerationEvents = 0, cpuEvents = 0;
        StringBuilder details = new StringBuilder();
        for (int i=0; i<2; i++) {
            OrtSession session = i==0 ? det : rec;
            String tag = i==0 ? "检测" : "识别";
            try {
                String trace = session.endProfiling();
                int hw=0,cpu=0;
                if (trace != null && !trace.isEmpty()) {
                    try (BufferedReader reader = new BufferedReader(new FileReader(trace))) {
                        String line;
                        while ((line=reader.readLine())!=null) {
                            hw += count(line, "NNAPIExecutionProvider");
                            cpu += count(line, "CPUExecutionProvider");
                        }
                    }
                    new File(trace).delete();
                }
                accelerationEvents += hw; cpuEvents += cpu;
                details.append(tag).append(": NNAPI ").append(hw).append(" / CPU ").append(cpu).append(" 个执行事件。");
            } catch (Exception e) {
                details.append(tag).append(": 运行分析不可用。");
            }
            if(i==0)details.append("\n");
        }
        if(accelerationEvents>0) {
            profileStatus = "已确认 NNAPI 承接部分算子（但无法仅凭此确定是 GPU 还是 NPU）。\n" + details;
        } else if (detNnapi || recNnapi) {
            profileStatus = "已启用 NNAPI，但未记录到 NNAPI 执行算子；可能实际回退 CPU。\n" + details;
        } else {
            profileStatus = "本次使用 ORT CPU 推理。\n" + details;
        }
        return profileStatus;
    }
    private static int count(String text, String needle) {
        int n=0, pos=0;
        while((pos=text.indexOf(needle,pos))>=0){n++;pos+=needle.length();}
        return n;
    }

    public List<Face> analyze(Bitmap original) throws Exception {
        if (original == null) return Collections.emptyList();
        final int ow = original.getWidth(), oh = original.getHeight();
        if (ow <= 0 || oh <= 0) return Collections.emptyList();
        float scale = Math.min((float)DET / ow, (float)DET / oh);
        int sw = Math.max(1, Math.round(ow * scale)), sh = Math.max(1, Math.round(oh * scale));
        Bitmap input = Bitmap.createBitmap(DET, DET, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(input);
        canvas.drawColor(Color.BLACK);
        canvas.drawBitmap(original, null, new Rect(0,0,sw,sh), new Paint(Paint.FILTER_BITMAP_FLAG));
        List<Candidate> candidates;
        try (OnnxTensor tensor = imageTensor(input, DET, 128f);
             OrtSession.Result output = det.run(Collections.singletonMap(detInput, tensor))) {
            candidates = decode(output, scale, ow, oh);
        } finally { input.recycle(); }
        ArrayList<Face> faces = new ArrayList<>();
        for (Candidate c : candidates) {
            Bitmap crop = align(original, c.landmarks);
            try (OnnxTensor tensor = imageTensor(crop, REC, 127.5f);
                 OrtSession.Result result = rec.run(Collections.singletonMap(recInput, tensor))) {
                FloatBuffer buffer = ((OnnxTensor)result.get(0)).getFloatBuffer();
                float[] vec = new float[buffer.remaining()];
                buffer.get(vec);
                double mag = 0;
                for (float v: vec) mag += v * v;
                mag = Math.sqrt(mag);
                if (mag < 1e-7) continue;
                for (int j=0; j<vec.length; j++) vec[j] /= (float)mag;
                faces.add(new Face(c.bbox, vec, c.score));
            } finally { crop.recycle(); }
        }
        return faces;
    }
    public static float cosine(float[] a, float[] b) {
        if (a.length != b.length) return -1f;
        float s=0;
        for(int i=0;i<a.length;i++)s+=a[i]*b[i];
        return s;
    }
    private OnnxTensor imageTensor(Bitmap bitmap, int size, float std) throws Exception {
        int count = size * size;
        int[] pix = new int[count];
        bitmap.getPixels(pix,0,size,0,0,size,size);
        ByteBuffer bytes = ByteBuffer.allocateDirect(count * 3 * 4).order(ByteOrder.nativeOrder());
        FloatBuffer f = bytes.asFloatBuffer();
        // OpenCV blobFromImage with swapRB=true: RGB in NCHW, matching InsightFace.
        for(int channel=0;channel<3;channel++) {
            for(int p:pix) {
                int v = channel==0?Color.red(p):(channel==1?Color.green(p):Color.blue(p));
                f.put((v - 127.5f)/std);
            }
        }
        f.flip();
        return OnnxTensor.createTensor(env, f, new long[]{1,3,size,size});
    }
    private static final class Candidate {
        float[] bbox, landmarks;
        float score;
        Candidate(float[] b, float[] k, float s) {bbox=b;landmarks=k;score=s;}
    }
    private List<Candidate> decode(OrtSession.Result result, float scale, int ow, int oh) throws Exception {
        int count=result.size(), fmc=(count==9||count==6)?3:5;
        if (count!=9 && count!=15) throw new IllegalStateException("SCRFD landmark model expected 9/15 outputs, got "+count);
        int[] strides=fmc==3?new int[]{8,16,32}:new int[]{8,16,32,64,128};
        int anchors=fmc==3?2:1;
        ArrayList<Candidate> list = new ArrayList<>();
        for (int i=0;i<fmc;i++) {
            FloatBuffer sb=((OnnxTensor)result.get(i)).getFloatBuffer();
            FloatBuffer bb=((OnnxTensor)result.get(i+fmc)).getFloatBuffer();
            FloatBuffer kb=((OnnxTensor)result.get(i+fmc*2)).getFloatBuffer();
            int stride=strides[i], gw=DET/stride, gh=DET/stride;
            int total=gw*gh*anchors;
            if(sb.remaining()<total||bb.remaining()<total*4||kb.remaining()<total*10)
                throw new IllegalStateException("Unexpected SCRFD output layout at stride "+stride);
            for(int p=0;p<total;p++) {
                float confidence=sb.get();
                if(confidence<0.35f) {
                    bb.position(bb.position()+4);
                    kb.position(kb.position()+10);
                    continue;
                }
                int grid=p/anchors, x=grid%gw, y=grid/gw;
                float cx=x*stride, cy=y*stride;
                float l=bb.get()*stride, t=bb.get()*stride, r=bb.get()*stride, b=bb.get()*stride;
                float[] box={clamp((cx-l)/scale,0,ow),clamp((cy-t)/scale,0,oh),
                        clamp((cx+r)/scale,0,ow),clamp((cy+b)/scale,0,oh)};
                float[] kp=new float[10];
                for(int j=0;j<5;j++) {
                    kp[j*2]=((cx+kb.get()*stride)/scale);
                    kp[j*2+1]=((cy+kb.get()*stride)/scale);
                }
                if(box[2]-box[0]>=12 && box[3]-box[1]>=12) list.add(new Candidate(box,kp,confidence));
            }
        }
        list.sort((a,b)->Float.compare(b.score,a.score));
        ArrayList<Candidate> keep=new ArrayList<>();
        for(Candidate c:list) {
            boolean overlap=false;
            for(Candidate k:keep) if(iou(c.bbox,k.bbox)>0.4f) {overlap=true;break;}
            if(!overlap) keep.add(c);
            if(keep.size()>=80) break;
        }
        return keep;
    }
    private static float clamp(float v,float lo,float hi){return Math.min(hi,Math.max(lo,v));}
    private static float iou(float[] a,float[] b) {
        float x1=Math.max(a[0],b[0]),y1=Math.max(a[1],b[1]);
        float x2=Math.min(a[2],b[2]),y2=Math.min(a[3],b[3]);
        float intersection=Math.max(0,x2-x1)*Math.max(0,y2-y1);
        float aa=Math.max(0,a[2]-a[0])*Math.max(0,a[3]-a[1]);
        float ba=Math.max(0,b[2]-b[0])*Math.max(0,b[3]-b[1]);
        return intersection/(aa+ba-intersection+1e-6f);
    }
    private static Bitmap align(Bitmap src,float[] kps) {
        double sx=0,sy=0,tx=0,ty=0;
        for(int i=0;i<5;i++){sx+=kps[i*2];sy+=kps[i*2+1];tx+=TEMPLATE[i][0];ty+=TEMPLATE[i][1];}
        sx/=5;sy/=5;tx/=5;ty/=5;
        double aa=0,bb=0,den=0;
        for(int i=0;i<5;i++){
            double x=kps[i*2]-sx,y=kps[i*2+1]-sy,u=TEMPLATE[i][0]-tx,v=TEMPLATE[i][1]-ty;
            aa+=x*u+y*v;bb+=x*v-y*u;den+=x*x+y*y;
        }
        float a=(float)(aa/Math.max(den,1e-6)), b=(float)(bb/Math.max(den,1e-6));
        float dx=(float)(tx-a*sx+b*sy), dy=(float)(ty-b*sx-a*sy);
        Matrix transform=new Matrix();
        transform.setValues(new float[]{a,-b,dx,b,a,dy,0,0,1});
        Bitmap face=Bitmap.createBitmap(REC,REC,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(face);
        canvas.drawColor(Color.BLACK);
        canvas.drawBitmap(src,transform,new Paint(Paint.FILTER_BITMAP_FLAG));
        return face;
    }
    @Override public void close() throws Exception { det.close();rec.close(); }
}
