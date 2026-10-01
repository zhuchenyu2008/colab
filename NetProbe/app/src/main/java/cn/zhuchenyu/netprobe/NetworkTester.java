package cn.zhuchenyu.netprobe;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DnsResolver;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.os.CancellationSignal;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public final class NetworkTester {
    static final class Result {
        boolean ok; String ip, error, dnsServers, dnsError;
        Long latencyMs, dnsMs; Double speedMbps; Integer httpCode;
        int bytes; boolean dnsMeasured;
    }
    private static final int LIMIT = 1024 * 1024;
    private static final long DNS_TIMEOUT = 5000;

    static Result probe(Context context, String rawUrl, boolean speed, boolean dns) {
        Result r = new Result(); r.dnsMeasured = dns;
        HttpURLConnection c = null;
        try {
            URL u = new URL(rawUrl);
            List<InetAddress> addrs = Collections.emptyList();
            if (dns) {
                DnsData d = dns(context, u.getHost());
                r.dnsMs = d.ms; r.dnsServers = d.servers; r.dnsError = d.error; addrs = d.addresses;
            }
            if (addrs.isEmpty()) addrs = java.util.Arrays.asList(InetAddress.getAllByName(u.getHost()));
            if (!addrs.isEmpty()) r.ip = addrs.get(0).getHostAddress();

            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(5000); c.setReadTimeout(7000); c.setInstanceFollowRedirects(true);
            c.setUseCaches(false); c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", "NetProbe/1.1 Android");
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Cache-Control", "no-cache");
            c.setRequestProperty("Range", speed ? "bytes=0-" + (LIMIT - 1) : "bytes=0-0");

            long start = System.nanoTime();
            int code = c.getResponseCode(); r.httpCode = code;
            BufferedInputStream in = new BufferedInputStream(code >= 400 ? c.getErrorStream() : c.getInputStream());
            byte[] buf = new byte[16384]; int first = in.read(buf);
            long firstNs = System.nanoTime(); r.latencyMs = (firstNs - start) / 1_000_000;
            int total = Math.max(first, 0);
            if (speed && first >= 0) {
                long ts = firstNs;
                while (total < LIMIT && (System.nanoTime() - ts) / 1_000_000 < 4000) {
                    int n = in.read(buf, 0, Math.min(buf.length, LIMIT - total));
                    if (n < 0) break; total += n;
                }
                long ms = Math.max(20, (System.nanoTime() - ts) / 1_000_000);
                r.speedMbps = total * 8.0 / (ms / 1000.0) / 1_000_000.0;
            }
            in.close(); r.bytes = total; r.ok = code >= 200 && code < 400;
            if (!r.ok) r.error = "HTTP " + code;
        } catch (Throwable t) {
            r.ok = false; r.error = err(t);
        } finally { if (c != null) c.disconnect(); }
        return r;
    }

    private static final class DnsData { Long ms; List<InetAddress> addresses = Collections.emptyList(); String servers, error; }

    private static DnsData dns(Context context, String host) {
        DnsData d = new DnsData(); d.servers = describeDns(context);
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        Network network = cm == null ? null : cm.getActiveNetwork();
        long start = System.nanoTime();
        if (Build.VERSION.SDK_INT < 29) {
            try {
                d.addresses = network == null ? java.util.Arrays.asList(InetAddress.getAllByName(host)) : java.util.Arrays.asList(network.getAllByName(host));
                d.ms = (System.nanoTime() - start) / 1_000_000;
            } catch (Throwable t) { d.error = err(t); }
            return d;
        }
        CountDownLatch latch = new CountDownLatch(1); CancellationSignal cancel = new CancellationSignal();
        final List<InetAddress>[] out = new List[]{Collections.emptyList()}; final String[] e = new String[1];
        Executor direct = Runnable::run;
        DnsResolver.getInstance().query(network, host,
                DnsResolver.FLAG_NO_CACHE_LOOKUP | DnsResolver.FLAG_NO_CACHE_STORE,
                direct, cancel, new DnsResolver.Callback<List<InetAddress>>() {
                    public void onAnswer(List<InetAddress> answer, int rcode) { out[0] = answer; if (rcode != 0) e[0] = "DNS RCODE " + rcode; latch.countDown(); }
                    public void onError(DnsResolver.DnsException error) { e[0] = error.getCause() != null ? error.getCause().getMessage() : "DNS error " + error.code; latch.countDown(); }
                });
        try {
            if (!latch.await(DNS_TIMEOUT, TimeUnit.MILLISECONDS)) { cancel.cancel(); d.error = "DNS 超时"; return d; }
            d.ms = (System.nanoTime() - start) / 1_000_000; d.addresses = out[0]; d.error = e[0];
            if (d.error == null && d.addresses.isEmpty()) d.error = "DNS 无有效结果";
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); d.error = "DNS 被中断"; }
        return d;
    }

    static String describeDns(Context context) {
        try {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class); Network n = cm.getActiveNetwork();
            LinkProperties lp = cm.getLinkProperties(n); if (lp == null) return "--";
            StringBuilder s = new StringBuilder(); for (InetAddress a : lp.getDnsServers()) { if (s.length() > 0) s.append(", "); s.append(a.getHostAddress()); }
            if (Build.VERSION.SDK_INT >= 28 && lp.isPrivateDnsActive()) {
                String p = lp.getPrivateDnsServerName(); s.append(p == null ? " · 私人DNS" : " · 私人DNS: " + p);
            }
            return s.length() == 0 ? "--" : s.toString();
        } catch (Throwable t) { return "--"; }
    }

    static String publicIp() {
        for (String ep : new String[]{"https://api64.ipify.org", "https://icanhazip.com"}) {
            HttpURLConnection c = null;
            try { c = (HttpURLConnection)new URL(ep).openConnection(); c.setConnectTimeout(3500); c.setReadTimeout(3500); BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream())); String v = br.readLine(); if (v != null) { v = v.trim(); if (!v.isEmpty() && v.length() < 80) return v; } }
            catch (Throwable ignored) {} finally { if (c != null) c.disconnect(); }
        } return null;
    }

    private static String err(Throwable t) {
        if (t instanceof java.net.SocketTimeoutException) return "超时";
        if (t instanceof java.net.UnknownHostException) return "DNS 解析失败";
        if (t instanceof javax.net.ssl.SSLException) return "TLS/证书失败";
        if (t instanceof java.net.ConnectException) return "无法建立连接";
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
