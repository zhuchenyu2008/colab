package com.zhuchenyu.batterystep;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

public final class BackgroundLogStore {
    private static final String FILE_NAME = "background-monitor.log";
    private static final long MAX_BYTES = 512 * 1024;
    private static final int KEEP_LINES = 2500;
    private static final Object LOCK = new Object();

    private BackgroundLogStore() {}

    public static void append(Context context, String category, String message) {
        if (context == null) return;
        synchronized (LOCK) {
            try {
                File file = new File(context.getFilesDir(), FILE_NAME);
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(new Date());
                String line = ts + "  [" + category + "]  " + message + "\n";
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    out.write(line.getBytes(StandardCharsets.UTF_8));
                }
                if (file.length() > MAX_BYTES) trimLocked(file);
            } catch (Exception ignored) {
            }
        }
    }

    public static String readAll(Context context) {
        synchronized (LOCK) {
            File file = new File(context.getFilesDir(), FILE_NAME);
            if (!file.exists()) return "暂无后台日志。";
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
            } catch (Exception e) {
                return "读取日志失败：" + e.getClass().getSimpleName();
            }
            return out.length() == 0 ? "暂无后台日志。" : out.toString();
        }
    }

    public static void clear(Context context) {
        synchronized (LOCK) {
            File file = new File(context.getFilesDir(), FILE_NAME);
            if (file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private static void trimLocked(File file) {
        Deque<String> tail = new ArrayDeque<>(KEEP_LINES);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.size() >= KEEP_LINES) tail.removeFirst();
                tail.addLast(line);
            }
        } catch (Exception ignored) {
            return;
        }

        try (FileOutputStream out = new FileOutputStream(file, false)) {
            for (String line : tail) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }
}
