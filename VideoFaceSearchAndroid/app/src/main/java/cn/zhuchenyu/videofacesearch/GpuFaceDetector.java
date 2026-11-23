
package cn.zhuchenyu.videofacesearch;

import android.content.res.AssetManager;
import android.graphics.Bitmap;

// Loads ncnn's Vulkan compute backend; independent from ONNX Runtime's NNAPI provider.
public final class GpuFaceDetector implements AutoCloseable {
    private boolean ready;
    static { System.loadLibrary("vfs_detector"); }

    GpuFaceDetector() { ready = nativeInit(null); }

    // AssetManager must be passed from an Android Context. No global activity references.
    GpuFaceDetector(AssetManager assets) { ready = nativeInit(assets); }

    public boolean isReady() { return ready; }

    public float[] detect(Bitmap image) {
        if(!ready) return new float[0];
        Bitmap b=image;
        if (image.getConfig()!=Bitmap.Config.ARGB_8888)
            b=image.copy(Bitmap.Config.ARGB_8888,false);
        if(b==null)throw new IllegalArgumentException("Cannot allocate RGBA bitmap");
        try { return nativeDetect(b); }
        finally { if(b!=image)b.recycle(); }
    }

    @Override public void close() {
        if(ready){nativeClose();ready=false;}
    }
    private native boolean nativeInit(AssetManager assets);
    private native float[] nativeDetect(Bitmap bmp);
    private native void nativeClose();
}
