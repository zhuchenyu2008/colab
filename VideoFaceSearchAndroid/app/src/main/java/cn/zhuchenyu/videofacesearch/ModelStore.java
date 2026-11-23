
package cn.zhuchenyu.videofacesearch;

import android.content.Context;
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
    ModelStore(Context context) { dir = new File(context.getFilesDir(), "models"); }

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
