package cn.zhuchenyu.netprobe;

import android.app.Application;
import android.content.SharedPreferences;

public class NetProbeApp extends Application {
    private static final String PREFS = "netprobe";
    private static final String KEY_SITES = "sites";

    @Override
    public void onCreate() {
        super.onCreate();
        migrateDefaultSites();
    }

    private void migrateDefaultSites() {
        SharedPreferences prefs = getSharedPreferences(PREFS, 0);
        String raw = prefs.getString(KEY_SITES, null);

        if (raw == null || raw.trim().isEmpty()) {
            raw = "baidu\t百度\thttps://www.baidu.com/\n"
                    + "bilibili\t哔哩哔哩\thttps://www.bilibili.com/\n"
                    + "github\tGitHub\thttps://github.com/\n"
                    + "cf\tCloudflare\thttps://www.cloudflare.com/\n"
                    + "google\tGoogle\thttps://www.google.com/\n"
                    + "youtube\tYouTube\thttps://www.youtube.com/";
        } else {
            if (!containsId(raw, "google")) {
                raw += "\ngoogle\tGoogle\thttps://www.google.com/";
            }
            if (!containsId(raw, "youtube")) {
                raw += "\nyoutube\tYouTube\thttps://www.youtube.com/";
            }
        }

        prefs.edit().putString(KEY_SITES, raw).apply();
    }

    private boolean containsId(String raw, String id) {
        String prefix = id + "\t";
        for (String line : raw.split("\n")) {
            if (line.startsWith(prefix)) return true;
        }
        return false;
    }
}
