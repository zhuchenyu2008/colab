package com.zhuchenyu.camerascanner;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.net.nsd.*;
import android.net.wifi.WifiManager;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

public class MainActivity extends Activity {
    private static final int BG=0xff101918, PANEL=0xff1c2b29, FG=0xffedf7f4, MUTED=0xffa1b9b3, GREEN=0xff66e6be, AMBER=0xffffca80;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private LinearLayout results;
    private TextView networkText, status, counts;
    private Button scan, deep, monitor, stop, share, otherToggle;
    private ProgressBar progress;
    private volatile Session current;
    private volatile Session lastSession;
    private boolean showOther=false;
    private volatile boolean watching=false;
    private String lastJson="";
    private Set<String> previous = new HashSet<>();
    private String previousScope="";
    private final Runnable next = () -> { if(watching && current==null) start(true); };

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        LinearLayout root=new LinearLayout(this); root.setOrientation(1); root.setBackgroundColor(BG);
        root.setPadding(dp(18),dp(14),dp(18),dp(10)); setContentView(root);
        TextView title=text("CAMERA / SCANNER",12,GREEN,true); root.addView(title);
        TextView big=text("局域网摄像头扫描",27,FG,true); root.addView(big);
        networkText=text("正在检查 Wi-Fi…",13,MUTED,false); root.addView(networkText);
        TextView intro=text("仅识别授权局域网中的视频协议端点；HTTP 和端口开放不算摄像头证据。",13,MUTED,false);
        intro.setPadding(0,dp(8),0,dp(12)); root.addView(intro);
        LinearLayout card=new LinearLayout(this); card.setOrientation(1); card.setPadding(dp(14),dp(12),dp(14),dp(12)); card.setBackground(shape(PANEL,16)); root.addView(card);
        counts=text("0 个已验证视频端点",18,FG,true); card.addView(counts);
        status=text("准备就绪",13,MUTED,false); status.setPadding(0,dp(8),0,dp(8)); card.addView(status);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setMax(100); progress.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN)); card.addView(progress,new LinearLayout.LayoutParams(-1,dp(8)));
        LinearLayout r1=new LinearLayout(this); root.addView(r1);
        scan=button("快速扫描",GREEN,BG); deep=button("深度扫描",PANEL,FG);
        addButton(r1,scan); addButton(r1,deep);
        LinearLayout r2=new LinearLayout(this); root.addView(r2);
        monitor=button("持续监测",PANEL,FG); stop=button("停止",PANEL,FG); share=button("导出",PANEL,FG);
        addButton(r2,monitor); addButton(r2,stop); addButton(r2,share);
        stop.setEnabled(false); share.setEnabled(false);
        scan.setOnClickListener(v->confirm(false,false)); deep.setOnClickListener(v->confirm(true,false));
        monitor.setOnClickListener(v->{if(watching){halt();}else{confirm(true,true);}});
        stop.setOnClickListener(v->halt());
        share.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,lastJson);startActivity(Intent.createChooser(i,"导出扫描结果"));});
        TextView label=text("视频协议检测结果",16,FG,true); label.setPadding(0,dp(12),0,dp(5)); root.addView(label);
        otherToggle=button("查看其他网络响应（非摄像头证据）",PANEL,MUTED);
        otherToggle.setVisibility(View.GONE);
        LinearLayout.LayoutParams ot=new LinearLayout.LayoutParams(-1,dp(44));
        ot.setMargins(0,0,0,dp(8));root.addView(otherToggle,ot);
        otherToggle.setOnClickListener(v->{
            showOther=!showOther;
            Session session=lastSession;
            if(session!=null)render(session,session.complete);
        });
        ScrollView scroll=new ScrollView(this); results=new LinearLayout(this); results.setOrientation(1); scroll.addView(results);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        results.addView(text("扫描结果将在这里显示。未发现不等于不存在。",14,MUTED,false));
        showNetwork();
    }
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable shape(int c,int r){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(r));return d;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private Button button(String s,int bg,int fg){Button b=new Button(this);b.setText(s);b.setTextSize(13);b.setAllCaps(false);b.setTextColor(fg);b.setBackground(shape(bg,12));return b;}
    private void addButton(LinearLayout row,Button b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),1);p.setMargins(dp(3),dp(7),dp(3),0);row.addView(b,p);}
    private void showNetwork(){try{Scope s=scope(false);networkText.setText(s.networkSummary());}catch(Exception e){networkText.setText("网络诊断："+e.getMessage());}}
    private void confirm(boolean thorough,boolean watch){
        if(current!=null)return;
        new AlertDialog.Builder(this).setTitle("确认网络授权").setMessage("请确认你拥有当前 Wi-Fi 网络或已获得网络管理者授权。本工具仅进行设备发现与只读服务识别，不尝试登录或访问视频。")
            .setNegativeButton("取消",null).setPositiveButton("已获授权，开始",(d,w)->{watching=watch;start(thorough);}).show();
    }
    private void start(boolean thorough){
        if(current!=null)return;
        Scope sc;
        try{sc=scope(thorough);}catch(Exception e){status.setText("无法开始："+e.getMessage());watching=false;return;}
        if(!previousScope.equals(sc.label())){previous.clear();previousScope=sc.label();}
        networkText.setText(sc.networkSummary());
        Session s=new Session(sc,thorough);current=s;lastSession=s;showOther=false;otherToggle.setVisibility(View.GONE);scan.setEnabled(false);deep.setEnabled(false);
        stop.setEnabled(true);monitor.setEnabled(watching);monitor.setText(watching?"停止监测":"持续监测");
        share.setEnabled(false); progress.setProgress(0);status.setText("正在发现设备与服务…");
        results.removeAllViews();counts.setText("0 个已验证视频端点");
        new Thread(()->runScan(s),"camera-discovery").start();
    }
    private void halt(){
        watching=false;ui.removeCallbacks(next);
        Session s=current;if(s!=null){s.cancelled.set(true);s.pool.shutdownNow();}
        monitor.setText("持续监测");status.setText("正在停止…");
        if(s==null){status.setText("已停止");stop.setEnabled(false);scan.setEnabled(true);deep.setEnabled(true);monitor.setEnabled(true);}
    }
    @Override protected void onDestroy(){halt();super.onDestroy();}
    private static class Scope{
        final Network net;
        final long ip,low,high,gateway;
        final int prefix,actualPrefix;
        final boolean hasVpn;
        Scope(Network n,long i,int p,long gw,boolean vpn,boolean deep){
            net=n;ip=i;actualPrefix=p;gateway=gw;hasVpn=vpn;
            // Limit scanning to an explicitly reported range, and never cross the Wi-Fi subnet.
            // Fast: /24 maximum, deep: /22 maximum (1022 hosts).
            prefix=Math.max(deep?22:24,p);
            long mask=(0xffffffffL<<(32-prefix))&0xffffffffL;
            low=ip&mask;high=low|(~mask&0xffffffffL);
        }
        boolean contains(String a){try{long x=number(InetAddress.getByName(a));return x>=low&&x<=high&&x!=ip;}catch(Exception e){return false;}}
        String label(){return address(low)+"/"+prefix+(actualPrefix<prefix?"（实际 /"+actualPrefix+"，本次只扫描其中一部分）":"");}
        String networkSummary(){return "Wi-Fi "+address(ip)+"/"+actualPrefix+" · 网段 "+address(low)+"/"+prefix+(gateway==0?"":" · 网关 "+address(gateway))+(hasVpn?"\n检测到 VPN，已优先绑定实际 Wi-Fi 连接":"");}
    }
    private static long number(InetAddress a){byte[] b=a.getAddress();if(b.length!=4)throw new IllegalArgumentException();long x=0;for(byte v:b)x=(x<<8)|(v&255);return x;}
    private static String address(long i){return ((i>>24)&255)+"."+((i>>16)&255)+"."+((i>>8)&255)+"."+(i&255);}
    private static boolean privateIp(long i){
        return (i&0xff000000L)==0x0a000000L||(i&0xfff00000L)==0xac100000L||(i&0xffff0000L)==0xc0a80000L||(i&0xffc00000L)==0x64400000L;
    }
    private Scope scope(boolean deep) throws Exception{
        ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        if(cm==null)throw new Exception("系统网络服务不可用");
        Network active=cm.getActiveNetwork();
        NetworkCapabilities activeCaps=active==null?null:cm.getNetworkCapabilities(active);
        boolean vpn=activeCaps!=null&&activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        Scope best=null;int bestPriority=-1;
        for(Network n:cm.getAllNetworks()){
            NetworkCapabilities caps=cm.getNetworkCapabilities(n);
            if(caps==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))continue;
            LinkProperties props=cm.getLinkProperties(n);if(props==null)continue;
            long gateway=0;boolean hasDefaultRoute=false;
            for(RouteInfo route:props.getRoutes()){
                if(route.getGateway() instanceof Inet4Address){
                    long gw=number(route.getGateway());
                    if(gw!=0&&gateway==0)gateway=gw;
                    if(route.isDefaultRoute()){gateway=gw;hasDefaultRoute=true;}
                }
            }
            for(LinkAddress addr:props.getLinkAddresses()){
                if(!(addr.getAddress() instanceof Inet4Address))continue;
                long local=number(addr.getAddress());int p=addr.getPrefixLength();
                if(!privateIp(local)||p<=0||p>30)continue;
                int priority=(n.equals(active)?10:0)+(hasDefaultRoute?5:0)+(gateway!=0?2:0);
                if(best==null||priority>bestPriority){
                    best=new Scope(n,local,p,gateway,vpn,deep);
                    bestPriority=priority;
                }
            }
        }
        if(best==null)throw new Exception("没有找到带 IPv4 地址的 Wi-Fi。请确认连接的是摄像头所在 Wi-Fi");
        return best;
    }
    private static class Device{
        final String ip;
        final Set<String> evidence=new ConcurrentSkipListSet<>();
        final Set<Integer> ports=new ConcurrentSkipListSet<>();
        volatile boolean onvif,rtsp,mdns,vendor,ssdp;
        Device(String a){ip=a;}
        // RTSP OPTIONS identifies a video-control protocol endpoint, not necessarily a physical camera.
        boolean confirmedVideoProtocol(){return onvif||rtsp;}
        boolean videoAdvertisementOnly(){return mdns&&!confirmedVideoProtocol();}
        String category(){return confirmedVideoProtocol()?"VIDEO_PROTOCOL_VERIFIED":videoAdvertisementOnly()?"VIDEO_SERVICE_ADVERTISED":"OTHER_NETWORK_SERVICE";}
        String type(){
            if(onvif&&rtsp)return "ONVIF + RTSP 视频端点";
            if(onvif)return "ONVIF 网络视频端点";
            if(rtsp)return "RTSP 服务端点";
            if(mdns)return "mDNS 视频服务广播（未验证）";
            return "其他网络响应（无摄像头证据）";
        }
    }
    private static class Session{
        final Scope sc;final boolean deep;final AtomicBoolean cancelled=new AtomicBoolean();
        final ExecutorService pool=Executors.newFixedThreadPool(32);
        final ConcurrentHashMap<String,Device> devices=new ConcurrentHashMap<>();
        final AtomicInteger done=new AtomicInteger();
        final AtomicInteger attempts=new AtomicInteger(),open=new AtomicInteger(),timeouts=new AtomicInteger(),refused=new AtomicInteger(),other=new AtomicInteger();
        final ConcurrentHashMap<String,AtomicInteger> errors=new ConcurrentHashMap<>();
        final AtomicBoolean networkBlocked=new AtomicBoolean(false);
        volatile String activeTransport="物理 Wi-Fi 直连（不经 VPN）";
        void logError(String label,Throwable ex){
            String reason=ex.getClass().getSimpleName();
            if(ex.getMessage()!=null)reason+=": "+ex.getMessage();
            Throwable cause=ex.getCause();
            if(cause!=null)reason+=" / "+cause.toString();
            if(reason.length()>210)reason=reason.substring(0,210);
            String key=label+" → "+reason.replace("\n"," ");
            if(errors.size()<14||errors.containsKey(key))errors.computeIfAbsent(key,k->new AtomicInteger()).incrementAndGet();
        }
        String errorSummary(){
            ArrayList<String> detail=new ArrayList<>();
            errors.forEach((k,v)->detail.add(v.get()+"× "+k));
            detail.sort(Comparator.naturalOrder());
            return detail.isEmpty()?"无":android.text.TextUtils.join("\n",detail);
        }
        volatile boolean complete=false;
        int total;final List<String> notes=Collections.synchronizedList(new ArrayList<>());
        Session(Scope s,boolean d){sc=s;deep=d;}
        Device get(String ip){return devices.computeIfAbsent(ip,Device::new);}
    }
    private boolean preflightPhysicalWifi(Session s){
        if(s.sc.gateway==0){s.notes.add("没有获取到网关，直接测试实际 Wi-Fi 子网");return true;}
        String gateway=address(s.sc.gateway);
        try(Socket test=s.sc.net.getSocketFactory().createSocket()){
            try{
                test.connect(new InetSocketAddress(gateway,80),1200);
                s.notes.add("Wi-Fi 直连预检：可连接网关");
            }catch(ConnectException e){
                s.notes.add("Wi-Fi 直连预检：网关端口拒绝（说明已到达网关）");
            }catch(SocketTimeoutException e){
                s.notes.add("Wi-Fi 直连预检：网关 HTTP 端口无响应，继续尝试设备");
            }
            return true;
        }catch(Exception e){
            s.logError("Wi-Fi 直连预检",e);
            s.notes.add("无法通过实际 Wi-Fi 建立扫描连接："+e.getClass().getSimpleName()+" "+String.valueOf(e.getMessage()));
            if(s.sc.hasVpn)s.notes.add("检测到 VPN；请在 VPN 的按应用分流中排除本 APP，或临时关闭 VPN 后再扫描。不会经 VPN 默认网络继续扫描。");
            s.networkBlocked.set(true);
            return false;
        }
    }
    private void runScan(Session s){
        WifiManager.MulticastLock lock=null;
        try{
            s.total=(int)(s.sc.high-s.sc.low-2);
            if(!preflightPhysicalWifi(s))return;
            WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
            if(wm!=null){lock=wm.createMulticastLock("camera-discovery");lock.setReferenceCounted(false);lock.acquire();}
            Future<?> onvif=s.pool.submit(()->udpDiscovery(s,true));
            Future<?> ssdp=s.pool.submit(()->udpDiscovery(s,false));
            Future<?> mdns=s.pool.submit(()->mdnsDiscovery(s));
            ArrayList<Future<?>> jobs=new ArrayList<>();
            for(long ip=s.sc.low+1;ip<s.sc.high&&!s.cancelled.get();ip++){
                if(ip==s.sc.ip)continue;String addr=address(ip);
                jobs.add(s.pool.submit(()->{try{probe(s,addr);}finally{int x=s.done.incrementAndGet();if(x%12==0||x==s.total)ui.post(()->render(s,false));}}));
            }
            for(Future<?> f:jobs){if(s.cancelled.get())break;try{f.get();}catch(Exception ignored){}}
            for(Future<?> f:Arrays.asList(onvif,ssdp,mdns)){try{f.get(5,TimeUnit.SECONDS);}catch(Exception ignored){}}
        }catch(Exception e){s.notes.add("扫描中断："+e.getClass().getSimpleName());}
        finally{if(lock!=null&&lock.isHeld())lock.release();s.pool.shutdownNow();ui.post(()->finish(s));}
    }
    private Socket connectOnRoute(Session s,String ip,int port,int timeout) throws IOException {
        // Never fall back to an unbound socket. That socket can be redirected through a
        // VPN transparent proxy, which falsely reports all hosts and ports as open.
        Socket sock=null;
        try{
            sock=s.sc.net.getSocketFactory().createSocket();
            sock.connect(new InetSocketAddress(ip,port),timeout);
            return sock;
        }catch(IOException|RuntimeException e){
            if(sock!=null)try{sock.close();}catch(IOException ignored){}
            s.logError("Wi-Fi 物理网络连接",e);
            if(e.getMessage()!=null && (e.getMessage().contains("EPERM")||e.getMessage().contains("Binding socket to network"))){
                s.networkBlocked.set(true);
            }
            throw e;
        }
    }
    private void probe(Session s,String ip){
        int[] ports=s.deep
            ?new int[]{554,8554,10554,8555,5544,7447,7448,8556,7070,10555,7554,80,81,443,8080,8081,8090,8000,5000,8888,8899,9000,37777,34567}
            :new int[]{554,8554,10554,8555,5544,80,443,8080,8000};
        for(int port:ports){
            if(s.cancelled.get()||s.networkBlocked.get()||Thread.currentThread().isInterrupted())return;
            s.attempts.incrementAndGet();
            try(Socket sock=connectOnRoute(s,ip,port,s.deep?850:600)){
                s.open.incrementAndGet();
                sock.setSoTimeout(s.deep?1400:1000);
                Device d=s.get(ip);d.ports.add(port);
                if(isVideoPort(port)){
                    d.evidence.add("TCP "+port+" 可连接；可能是 RTSP，等待协议确认");
                    try{
                        String reply=exchange(sock,"OPTIONS rtsp://"+ip+":"+port+"/ RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: CameraScanner/1.0\r\n\r\n");
                        if(isRtsp(reply)){
                            d.rtsp=true;d.evidence.add("RTSP 协议已确认："+firstLine(reply));
                        }else{
                            String second=tryAlternativeRtsp(s,ip,port);
                            if(isRtsp(second)){d.rtsp=true;d.evidence.add("RTSP 协议已确认："+firstLine(second));}
                            else if(!reply.isEmpty())d.evidence.add("非标准协议响应："+firstLine(reply));
                        }
                    }catch(IOException|RuntimeException e){
                        d.evidence.add("视频端口开放，但 RTSP 握手失败："+e.getClass().getSimpleName());
                        s.logError("RTSP握手",e);
                    }
                }else if(port==80||port==81||port==8080||port==8081||port==8090||port==8000||port==8899||port==8888){
                    d.evidence.add("HTTP 常用端口 "+port+" 可连接");
                    try{
                        String reply=exchange(sock,"HEAD / HTTP/1.1\r\nHost: "+ip+"\r\nConnection: close\r\nUser-Agent: CameraScanner/1.0\r\n\r\n");
                        if(reply.startsWith("HTTP/")){d.evidence.add("HTTP "+port+"："+firstLine(reply));markVendor(d,reply);}
                    }catch(IOException e){s.logError("HTTP握手",e);}
                }else d.evidence.add("TCP "+port+" 可连接（仅端口证据）");
            }catch(SocketTimeoutException e){s.timeouts.incrementAndGet();}
            catch(ConnectException e){s.refused.incrementAndGet();}
            catch(SecurityException e){s.logError("安全策略",e);s.other.incrementAndGet();return;}
            catch(IOException|RuntimeException e){s.other.incrementAndGet();s.logError("TCP "+port,e);}
        }
    }
    private static boolean isVideoPort(int p){
        for(int v:new int[]{554,8554,10554,8555,5544,7447,7448,8556,7070,10555,7554})if(v==p)return true;
        return false;
    }
    private static boolean isRtsp(String response){
        return response!=null && response.matches("(?is)^\\s*RTSP/\\d+\\.\\d+\\s+\\d{3}.*");
    }
    private String tryAlternativeRtsp(Session s,String ip,int port){
        if(s.cancelled.get()||s.networkBlocked.get())return "";
        try(Socket sock=connectOnRoute(s,ip,port,1100)){
            sock.setSoTimeout(1200);
            return exchange(sock,"OPTIONS * RTSP/1.0\r\nCSeq: 2\r\nUser-Agent: CameraScanner/1.0\r\n\r\n");
        }catch(Exception e){return "";}
    }
    private static String exchange(Socket s,String request)throws IOException{
        s.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));s.getOutputStream().flush();
        ByteArrayOutputStream b=new ByteArrayOutputStream();InputStream in=s.getInputStream();byte[] buf=new byte[1024];
        while(b.size()<8192){int n;try{n=in.read(buf);}catch(SocketTimeoutException e){break;}if(n<0)break;b.write(buf,0,n);String t=b.toString("UTF-8");if(t.contains("\r\n\r\n"))break;}
        return b.toString("UTF-8");
    }
    private static String firstLine(String s){String t=s.split("\\r?\\n",2)[0].replaceAll("[\\p{Cntrl}]","");return t.length()>140?t.substring(0,140):t;}
    private static void markVendor(Device d,String reply){
        String l=reply.toLowerCase(Locale.ROOT);
        for(String token:new String[]{"hikvision","dahua","reolink","amcrest","axis communications","ip camera","network camera","ipcamera","networkvideo","network video transmitter","ezviz","imou","uniview","vivotek","tapo camera","foscam"}){
            if(l.contains(token)){d.vendor=true;d.evidence.add("公开服务特征："+token);}
        }
    }
    private void udpDiscovery(Session s,boolean onvif){
        try(DatagramSocket sock=new DatagramSocket(null)){
            try{s.sc.net.bindSocket(sock);}
            catch(Exception e){s.logError("UDP Wi-Fi 绑定",e);s.notes.add("UDP 物理 Wi-Fi 绑定失败，已跳过此协议，不使用 VPN 默认路由");return;}
            sock.bind(new InetSocketAddress(0));sock.setSoTimeout(350);
            String payload=onvif?"<?xml version=\"1.0\" encoding=\"UTF-8\"?><s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:a=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\"><s:Header><a:MessageID>urn:uuid:"+UUID.randomUUID()+"</a:MessageID><a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To><a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action></s:Header><s:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></s:Body></s:Envelope>":"M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n";
            byte[] b=payload.getBytes(StandardCharsets.UTF_8);
            DatagramPacket out=new DatagramPacket(b,b.length,InetAddress.getByName("239.255.255.250"),onvif?3702:1900);
            sock.send(out);sock.send(out);
            long end=SystemClock.elapsedRealtime()+(s.deep?3500:2200);
            while(!s.cancelled.get()&&SystemClock.elapsedRealtime()<end){
                byte[] buf=new byte[16384];DatagramPacket in=new DatagramPacket(buf,buf.length);
                try{sock.receive(in);}catch(SocketTimeoutException e){continue;}
                String ip=in.getAddress().getHostAddress();if(!s.sc.contains(ip))continue;
                String reply=new String(in.getData(),0,in.getLength(),StandardCharsets.UTF_8);String l=reply.toLowerCase(Locale.ROOT);
                if(onvif&&l.contains("probematch")){
                    Device d=s.get(ip);
                    if(l.contains("networkvideotransmitter")||l.contains("onvif.org/type/video_encoder")){d.onvif=true;d.evidence.add("WS-Discovery / ONVIF 视频设备类型");}
                    else d.evidence.add("WS-Discovery ProbeMatch（类型待确认）");
                    markVendor(d,reply);
                }else if(!onvif&&l.startsWith("http/1.1 200")){
                    Device d=s.get(ip);d.evidence.add("SSDP / UPnP 响应");
                    if(l.contains("digital security camera")||l.contains("networkcamera")||l.contains("ipcamera"))d.ssdp=true;
                    markVendor(d,reply);
                }
            }
        }catch(Exception e){s.logError(onvif?"ONVIF":"SSDP",e);s.notes.add((onvif?"ONVIF":"SSDP")+" 发现不可用："+e.getClass().getSimpleName()+" / "+e.getMessage());}
    }
    private void mdnsDiscovery(Session s){
        NsdManager nsd=(NsdManager)getSystemService(NSD_SERVICE);if(nsd==null)return;
        List<NsdManager.DiscoveryListener> listeners=new ArrayList<>();
        AtomicBoolean resolving=new AtomicBoolean();
        for(String type:new String[]{"_rtsp._tcp.","_onvif._tcp."}){
            if(s.cancelled.get())break;
            NsdManager.DiscoveryListener listener=new NsdManager.DiscoveryListener(){
                public void onDiscoveryStarted(String a){}
                public void onDiscoveryStopped(String a){}
                public void onStartDiscoveryFailed(String a,int code){s.notes.add("mDNS "+type+" 启动失败 "+code);}
                public void onStopDiscoveryFailed(String a,int code){}
                public void onServiceLost(NsdServiceInfo i){}
                public void onServiceFound(NsdServiceInfo i){
                    if(s.cancelled.get()||!resolving.compareAndSet(false,true))return;
                    try{nsd.resolveService(i,new NsdManager.ResolveListener(){
                        public void onResolveFailed(NsdServiceInfo x,int e){resolving.set(false);}
                        public void onServiceResolved(NsdServiceInfo x){
                            try{InetAddress a=x.getHost();if(a!=null&&s.sc.contains(a.getHostAddress())&&!s.cancelled.get()){
                                Device d=s.get(a.getHostAddress());d.mdns=true;d.evidence.add("mDNS 服务："+type+" "+firstLine(x.getServiceName()));markVendor(d,x.getServiceName());
                            }}finally{resolving.set(false);}
                        }
                    });}catch(Exception e){resolving.set(false);}
                }
            };
            try{nsd.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,listener);listeners.add(listener);}catch(Exception e){s.notes.add("mDNS 不可用");}
        }
        long end=SystemClock.elapsedRealtime()+(s.deep?4000:2500);
        while(!s.cancelled.get()&&SystemClock.elapsedRealtime()<end){try{Thread.sleep(100);}catch(InterruptedException e){break;}}
        for(NsdManager.DiscoveryListener l:listeners){try{nsd.stopServiceDiscovery(l);}catch(Exception ignored){}}
    }
    private List<Device> sorted(Session s){
        List<Device> list=new ArrayList<>(s.devices.values());
        list.sort(Comparator.comparingInt((Device d)->d.onvif&&d.rtsp?3:d.confirmedVideoProtocol()?2:d.mdns?1:0)
            .reversed().thenComparingLong(d->{try{return number(InetAddress.getByName(d.ip));}catch(Exception e){return 0;}}));
        return list;
    }
    private void render(Session s,boolean complete){
        if(lastSession!=s)return;
        List<Device> all=sorted(s);
        List<Device> confirmed=new ArrayList<>(),advertised=new ArrayList<>(),other=new ArrayList<>();
        for(Device d:all){
            if(d.confirmedVideoProtocol())confirmed.add(d);
            else if(d.videoAdvertisementOnly())advertised.add(d);
            else other.add(d);
        }
        counts.setText(confirmed.size()+" 个已验证视频端点 · "+advertised.size()+" 个待核实广播");
        progress.setProgress(s.networkBlocked.get()?0:(complete&&!s.cancelled.get()?100:Math.min(99,100*s.done.get()/Math.max(1,s.total))));
        if(!complete)status.setText("扫描 "+s.done.get()+" / "+s.total+" 个 IP · 发现视频端点 "+confirmed.size()+" 个");
        if(complete)status.setText(s.networkBlocked.get()?"系统禁止绑定 Wi-Fi，扫描未完成":"扫描完成 · "+confirmed.size()+" 个视频协议端点 · 其他响应 "+other.size()+" 个");
        otherToggle.setVisibility(other.isEmpty()?View.GONE:View.VISIBLE);
        otherToggle.setText((showOther?"收起":"查看")+"其他响应 IP（"+other.size()+"）· 不代表摄像头");
        results.removeAllViews();
        if(confirmed.isEmpty()){
            String message=s.networkBlocked.get()?"物理 Wi-Fi 被系统限制，不能得出设备结论。":
                complete?"未收到可验证的 RTSP 或 ONVIF 视频协议响应；不能据此排除摄像头。":"正在扫描视频协议…";
            TextView empty=text(message,14,MUTED,false);
            empty.setPadding(dp(6),dp(10),dp(6),dp(12));results.addView(empty);
        }
        for(Device d:confirmed)renderDeviceCard(d,true);
        if(!advertised.isEmpty()){
            TextView section=text("仅发现视频服务广播（需要进一步验证）",14,AMBER,true);
            section.setPadding(0,dp(9),0,dp(8));results.addView(section);
            for(Device d:advertised)renderDeviceCard(d,false);
        }
        if(showOther&&!other.isEmpty()){
            TextView section=text("其他网络响应 · 不能当作摄像头",14,MUTED,true);
            section.setPadding(0,dp(12),0,dp(8));results.addView(section);
            for(Device d:other)renderOtherCard(d);
        }
        if(complete){
            TextView label=text("诊断数据（不计入摄像头识别）",14,MUTED,true);
            label.setPadding(0,dp(14),0,dp(6));results.addView(label);
            String finding;
            if(s.networkBlocked.get())finding="Android 拒绝绑定物理 Wi-Fi。已中止，避免 VPN/代理制造虚假的设备响应。";
            else if(s.cancelled.get())finding="扫描被用户停止，仅有部分检测数据。";
            else if(confirmed.isEmpty())finding="尚无视频协议证据；其他设备开放 HTTP/TCP 端口不能说明是摄像头。";
            else finding="发现了网络视频协议端点；协议确认不等于已经确认物理摄像头。";
            String detail="Wi-Fi："+s.sc.networkSummary()+"\n检测 IP："+s.done.get()+"/"+s.total+
                " · TCP 探测："+s.attempts.get()+" · 可连接："+s.open.get()+
                "\n超时："+s.timeouts.get()+" · 拒绝："+s.refused.get()+" · 其他错误："+s.other.get()+
                "\n结论："+finding+"\n连接路径："+s.activeTransport+
                "\n异常摘要："+s.errorSummary()+"\n协议发现备注："+s.notes;
            TextView diag=text(detail,12,MUTED,false);
            diag.setPadding(dp(8),dp(4),dp(8),dp(10));results.addView(diag);
        }
    }
    private void renderDeviceCard(Device d,boolean verified){
        LinearLayout card=new LinearLayout(this);card.setOrientation(1);
        card.setPadding(dp(12),dp(12),dp(12),dp(12));card.setBackground(shape(PANEL,12));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,0,0,dp(8));results.addView(card,p);
        card.addView(text(d.ip+" · "+d.type(),16,verified?GREEN:AMBER,true));
        card.addView(text(verified?"已验证视频协议，但未确认是独立摄像头":"仅服务广播，不等于协议已成功建立",12,MUTED,false));
        ArrayList<String> important=new ArrayList<>();
        for(String e:d.evidence){
            if((d.onvif&&e.contains("ONVIF"))||(d.rtsp&&e.contains("RTSP 协议已确认"))||
               (d.mdns&&e.contains("mDNS"))||e.contains("公开服务特征"))important.add(e);
        }
        if(important.isEmpty())important.addAll(d.evidence);
        for(String e:important)card.addView(text("• "+e,12,FG,false));
        card.addView(text("全部端口信息可在导出 JSON 中查看",12,MUTED,false));
    }
    private void renderOtherCard(Device d){
        LinearLayout card=new LinearLayout(this);card.setOrientation(1);
        card.setPadding(dp(12),dp(10),dp(12),dp(10));card.setBackground(shape(PANEL,12));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,0,0,dp(6));results.addView(card,p);
        card.addView(text(d.ip+" · 其他网络服务",14,FG,true));
        card.addView(text("响应端口："+d.ports+"（不构成摄像头证据）",12,MUTED,false));
    }
    private void finish(Session s){
        if(current!=s)return;
        s.complete=true;render(s,true);List<Device> list=sorted(s);Set<String> now=new HashSet<>();JSONArray records=new JSONArray();
        try{
            for(Device d:list){if(d.confirmedVideoProtocol())now.add(d.ip);JSONObject j=new JSONObject();j.put("ip",d.ip);j.put("type",d.type());j.put("category",d.category());j.put("videoProtocolVerified",d.confirmedVideoProtocol());j.put("physicalCameraConfirmed",false);j.put("onvif",d.onvif);j.put("rtsp",d.rtsp);j.put("mdns",d.mdns);j.put("evidence",new JSONArray(d.evidence));j.put("ports",new JSONArray(d.ports));records.put(j);}
            JSONObject report=new JSONObject();report.put("scope",s.sc.label());report.put("wifiIp",address(s.sc.ip));report.put("wifiActualPrefix",s.sc.actualPrefix);report.put("wifiGateway",s.sc.gateway==0?"":address(s.sc.gateway));report.put("vpnDetected",s.sc.hasVpn);report.put("timestamp",System.currentTimeMillis());report.put("cancelled",s.cancelled.get());report.put("testedHosts",s.done.get());report.put("totalHosts",s.total);report.put("tcpAttempts",s.attempts.get());report.put("tcpOpen",s.open.get());report.put("tcpTimeouts",s.timeouts.get());report.put("tcpRefused",s.refused.get());report.put("tcpOther",s.other.get());report.put("transport",s.activeTransport);report.put("networkBlocked",s.networkBlocked.get());report.put("scanTrustworthy",!s.networkBlocked.get()&&!s.cancelled.get());report.put("errorDetails",s.errorSummary());report.put("identificationPolicy","Only RTSP protocol responses or ONVIF NetworkVideoTransmitter discovery are verified video endpoints; neither proves a physical camera. HTTP/TCP openings and vendor names are not camera evidence. mDNS is advertisement only.");report.put("devices",records);report.put("notes",new JSONArray(s.notes));lastJson=report.toString(2);
            getPreferences(MODE_PRIVATE).edit().putString("last_scan",lastJson).apply();
        }catch(Exception ignored){}
        Set<String> added=new HashSet<>(now);added.removeAll(previous);boolean hadPrevious=!previous.isEmpty();if(!s.cancelled.get())previous=now;
        String msg=s.cancelled.get()?"已停止 · 部分结果":"扫描完成 · 仅根据视频协议识别";
        if(!s.notes.isEmpty())msg+=" · "+s.notes.size()+" 项发现限制";
        if(s.networkBlocked.get()){watching=false;msg="受系统网络限制：已停止扫描，未识别到真实摄像头";}
        if(watching&&!s.cancelled.get()){msg+=" · 45 秒后再次扫描";if(hadPrevious&&!added.isEmpty()){msg+=" · 新视频端点 "+added.size();Toast.makeText(this,"新发现的网络视频端点："+added.size(),Toast.LENGTH_LONG).show();}}
        status.setText(msg);share.setEnabled(true);current=null;scan.setEnabled(!watching);deep.setEnabled(!watching);monitor.setEnabled(true);stop.setEnabled(watching);
        if(watching&&!s.cancelled.get()&&!s.networkBlocked.get())ui.postDelayed(next,45000);else{watching=false;monitor.setText("持续监测");}
    }
}
