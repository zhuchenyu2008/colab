
package cn.zhuchenyu.videofacesearch;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.*;
import java.net.*;
import java.util.zip.*;

public final class ModelStore {
    public interface Progress { void update(String s); }
    private static final String[] URLS = {
        "https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_l.zip",
        "https://github.com/deepinsight/insightface/releases/download/model-zoo/buffalo_l.zip"
    };
    private final File dir;
    private final AssetManager assets;
    ModelStore(Context context) {
        dir = new File(context.getFilesDir(), "models");
        assets=context.getAssets();
    }
    // Converted Vulkan weights are private app data, not cacheDir: updates preserve these files.
    public File ncnnDir(){return new File(dir,"ncnn");}
    public File ncnnParam(){return new File(ncnnDir(),"w600k_r50.ncnn.param");}
    public File ncnnBin(){return new File(ncnnDir(),"w600k_r50.ncnn.bin");}
    public boolean ncnnReady(){return ncnnParam().length()>1000 && ncnnBin().length()>100_000_000L;}
    public void ensureNcnnCached(Progress progress)throws Exception { synchronized(ModelStore.class){
        if(ncnnReady())return;
        File folder=ncnnDir();
        if(!folder.exists()&&!folder.mkdirs())throw new IOException("无法建立模型缓存目录");
        // v0.8.0 migration installer includes the same FP32 weights; subsequent lean updates do not.
        String[] packaged=assets.list("");
        boolean hasModel=false;
        if(packaged!=null)for(String name:packaged)if("w600k_r50.ncnn.bin".equals(name))hasModel=true;
        if(!hasModel)throw new IOException("未检测到已迁移的 Vulkan 模型；请先运行含模型迁移功能的完整版，或者使用 ONNX CPU 兼容模式");
        progress.update("首次将已经安装的 Vulkan 模型保存到持久目录；后续轻量更新将复用，不需要再次下载...");
        copyAsset("w600k_r50.ncnn.param",ncnnParam());
        copyAsset("w600k_r50.ncnn.bin",ncnnBin());
        if(!ncnnReady())throw new IOException("Vulkan 模型缓存不完整");
        progress.update("Vulkan 模型迁移成功。后续安装轻量版可直接复用。");
    }
    }
    private void copyAsset(String name,File target)throws Exception{
        if(target.length()>(name.endsWith(".bin")?100_000_000L:1000L))return;
        File temp=new File(target.getParentFile(),target.getName()+".part");
        if(temp.exists())temp.delete();
        try(InputStream in=assets.open(name,AssetManager.ACCESS_STREAMING);
            OutputStream out=new BufferedOutputStream(new FileOutputStream(temp))){
            byte[] b=new byte[256*1024];int n;
            while((n=in.read(b))!=-1)out.write(b,0,n);
        }catch(Exception e){temp.delete();throw e;}
        if(!temp.renameTo(target)){temp.delete();throw new IOException("模型无法移动到持久目录: "+name);}
    }

    public File detector() { return new File(dir, "det_10g.onnx"); }
    public File recognizer() { return new File(dir, "w600k_r50.onnx"); }
    public boolean ready() { return detector().length() > 10_000_000L && recognizer().length() > 100_000_000L; }

    public void ensure(Progress progress) throws Exception {
        if (ready()) return;
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create model directory");
        Exception last = null;
        for (String url : URLS) {
            File zip = new File(dir, "buffalo_l.part");
            try {
                progress.update("首次下载 InsightFace buffalo_l 模型（约 200MB）...");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(20000);
                c.setReadTimeout(60000);
                c.setRequestProperty("User-Agent", "VideoFaceSearchAndroid/0.1");
                int code = c.getResponseCode();
                if (code != 200) throw new IOException("Download HTTP " + code);
                long total = c.getContentLengthLong();
                try (InputStream in = new BufferedInputStream(c.getInputStream());
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(zip))) {
                    byte[] buffer = new byte[128 * 1024];
                    long done = 0, lastReport = 0;
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                        done += n;
                        if (done - lastReport > 5_000_000L) {
                            final long current = done;
                            progress.update("下载模型 " + (total > 0 ? (100 * current / total) + "%" : (current / 1_000_000L) + " MB"));
                            lastReport = done;
                        }
                    }
                } finally { c.disconnect(); }
                progress.update("解压检测和识别模型...");
                extract(zip);
                if (!ready()) throw new IOException("Model pack missing detector or recognizer");
                zip.delete();
                progress.update("模型就绪，可离线使用");
                return;
            } catch (Exception e) {
                last = e;
                zip.delete();
                detector().delete();
                recognizer().delete();
            }
        }
        throw new IOException("模型下载失败；请检查 GitHub Release 的网络连接: " + last, last);
    }
    private void extract(File file) throws Exception {
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                File dest;
                if (name.endsWith("/det_10g.onnx") || name.equals("det_10g.onnx")) dest = detector();
                else if (name.endsWith("/w600k_r50.onnx") || name.equals("w600k_r50.onnx")) dest = recognizer();
                else { in.closeEntry(); continue; }
                File tmp = new File(dest.getAbsolutePath() + ".part");
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
                    byte[] b = new byte[128 * 1024];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                }
                if (!tmp.renameTo(dest)) throw new IOException("Cannot move model " + dest.getName());
                in.closeEntry();
            }
        }
    }
}
