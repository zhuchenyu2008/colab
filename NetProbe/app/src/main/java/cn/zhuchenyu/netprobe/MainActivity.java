package cn.zhuchenyu.netprobe;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;

import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    static class Site { String id,name,url; Site(String i,String n,String u){id=i;name=n;url=u;} }
    static class Stat {
        String ip="--", err, dnsServers="--", dnsErr; Long latency,dns; Double speed; int checks,fail,consecutive,dnsChecks,dnsFail; boolean testing;
        final ArrayDeque<Long> lat = new ArrayDeque<>();
        synchronized void apply(NetworkTester.Result r){ checks++; if(r.ok){consecutive=0; if(r.latencyMs!=null){latency=r.latencyMs;lat.add(r.latencyMs);while(lat.size()>20)lat.removeFirst();} if(r.ip!=null)ip=r.ip;if(r.speedMbps!=null)speed=r.speedMbps;err=null;}else{fail++;consecutive++;err=r.error;if(r.ip!=null)ip=r.ip;} if(r.dnsMeasured){dnsChecks++;dnsServers=r.dnsServers==null?dnsServers:r.dnsServers;if(r.dnsMs!=null&&r.dnsError==null){dns=r.dnsMs;dnsErr=null;}else{dnsFail++;dnsErr=r.dnsError;}} }
        synchronized double rate(){return checks==0?0:(checks-fail)*100.0/checks;} synchronized double dnsRate(){return dnsChecks==0?0:(dnsChecks-dnsFail)*100.0/dnsChecks;}
        synchronized String avg(){ if(lat.isEmpty())return "--"; long s=0;for(long v:lat)s+=v;return Math.round(s/(double)lat.size())+" ms"; }
    }

    final int BG=Color.rgb(11,15,20),CARD=Color.rgb(20,26,34),BORDER=Color.rgb(43,53,66),TEXT=Color.rgb(239,244,248),SUB=Color.rgb(157,170,185),ACC=Color.rgb(124,255,178),WARN=Color.rgb(255,203,107),BAD=Color.rgb(255,112,112);
    final ExecutorService pool=Executors.newFixedThreadPool(6); final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(); ScheduledFuture<?> monitorTask; boolean monitoring; int round;
    final List<Site> sites=Collections.synchronizedList(new ArrayList<>()); final Map<String,Stat> stats=new ConcurrentHashMap<>(); final Map<String,TextView> metrics=new ConcurrentHashMap<>(); final Map<String,Button> testButtons=new ConcurrentHashMap<>();
    final ArrayDeque<String> logs=new ArrayDeque<>(); final Object logLock=new Object();
    LinearLayout list; TextView network,status,logView; Button monitor;

    public void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);load();setContentView(ui());render();addLog("APP","NetProbe 1.2.0 启动；站点="+sites.size());refreshNetwork();}
    public void onDestroy(){if(monitorTask!=null)monitorTask.cancel(true);scheduler.shutdownNow();pool.shutdownNow();super.onDestroy();}

    View ui(){ LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(14),dp(18),dp(18));root.setBackgroundColor(BG);
        TextView logo=t("NETPROBE",13,ACC,true);root.addView(logo);root.addView(t("手机网络质量检测",27,TEXT,true));root.addView(t("DNS 延迟 · 访问延迟 · 下载速度 · 稳定性 · IP",13,SUB,false));
        network=t("当前网络\n获取中…",13,SUB,false); network.setPadding(dp(14),dp(12),dp(14),dp(12));network.setBackground(box(CARD,BORDER));root.addView(network,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout a=row(); Button all=btn("测试全部",true);all.setOnClickListener(v->all());a.addView(all,lp());monitor=btn("持续监测",false);monitor.setOnClickListener(v->toggle());a.addView(monitor,lp());root.addView(a);
        LinearLayout b=row();Button add=btn("＋ 添加网站",false);add.setOnClickListener(v->add());b.addView(add,lp());Button refresh=btn("刷新网络",false);refresh.setOnClickListener(v->refreshNetwork());b.addView(refresh,lp());root.addView(b);
        status=t("就绪",12,SUB,false);root.addView(status);

        LinearLayout logHead=row();logHead.addView(t("运行日志",13,TEXT,true),new LinearLayout.LayoutParams(0,-2,1));Button copy=btn("复制全部日志",false);copy.setOnClickListener(v->copyLogs());logHead.addView(copy,new LinearLayout.LayoutParams(dp(118),dp(40)));Button clear=btn("清空",false);clear.setOnClickListener(v->clearLogs());logHead.addView(clear,new LinearLayout.LayoutParams(dp(70),dp(40)));root.addView(logHead);
        logView=t("暂无日志",10,SUB,false);logView.setTypeface(android.graphics.Typeface.MONOSPACE);logView.setPadding(dp(10),dp(8),dp(10),dp(8));logView.setBackground(box(CARD,BORDER));logView.setMaxLines(6);root.addView(logView,new LinearLayout.LayoutParams(-1,dp(112)));

        list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);ScrollView sv=new ScrollView(this);sv.addView(list);root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));return root; }

    void render(){list.removeAllViews();metrics.clear();testButtons.clear();synchronized(sites){for(Site s:sites){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(14),dp(12),dp(14),dp(12));c.setBackground(box(CARD,BORDER));
            LinearLayout head=row();LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);names.addView(t(s.name,18,TEXT,true));names.addView(t(s.url,11,SUB,false));head.addView(names,new LinearLayout.LayoutParams(0,-2,1));Button test=btn("测试",false);test.setOnClickListener(v->test(s));head.addView(test,new LinearLayout.LayoutParams(dp(70),dp(40)));Button del=btn("删除",false);del.setOnClickListener(v->remove(s));head.addView(del,new LinearLayout.LayoutParams(dp(70),dp(40)));c.addView(head);
            TextView m=t("",13,TEXT,false);m.setTypeface(android.graphics.Typeface.MONOSPACE);m.setPadding(0,dp(10),0,0);c.addView(m);metrics.put(s.id,m);testButtons.put(s.id,test);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.bottomMargin=dp(10);list.addView(c,cp);update(s.id);}} }

    void update(String id){runOnUiThread(()->{TextView v=metrics.get(id);Stat s=stats.get(id);if(v==null||s==null)return;synchronized(s){String dns=s.dns==null?"--":s.dns+" ms";String lat=s.latency==null?"--":s.latency+" ms";String sp=s.speed==null?"--":String.format(Locale.US,"%.2f Mbps",s.speed);String rate=s.checks==0?"--":String.format(Locale.US,"%.1f%%",s.rate());String dr=s.dnsChecks==0?"--":String.format(Locale.US,"%.1f%%",s.dnsRate());String e=s.err==null?"":"\n最近错误: "+s.err;String de=s.dnsErr==null?"":" · DNS错误: "+s.dnsErr;v.setText("IP  "+s.ip+"\nDNS "+dns+" · 成功率 "+dr+" · "+s.dnsServers+de+"\n访问 "+lat+" · 平均 "+s.avg()+" · 下载 "+sp+"\n稳定 "+rate+" · 探测 "+s.checks+" · 失败 "+s.fail+" · 连续失败 "+s.consecutive+e);Button tb=testButtons.get(id);if(tb!=null){tb.setEnabled(!s.testing);tb.setText(s.testing?"测试中":"测试");}}});}

    void test(Site site){Stat st=stats.computeIfAbsent(site.id,k->new Stat());st.testing=true;update(site.id);status.setText("正在测试 "+site.name+"…");addLog("TEST","开始手动测试 "+site.name+" "+site.url);pool.execute(()->{try{for(int i=0;i<5;i++){boolean full=i==0;NetworkTester.Result r=NetworkTester.probe(this,site.url,full,full);st.apply(r);addProbeLog(site,r,full?"完整":"延迟");update(site.id);if(i<4)Thread.sleep(250);}}catch(Exception e){addLog("ERROR",site.name+" 测试线程异常: "+safe(e.getMessage()));}finally{st.testing=false;update(site.id);addLog("TEST",site.name+" 手动测试完成");runOnUiThread(()->status.setText(site.name+" 测试完成"));}});}
    void all(){List<Site> copy; synchronized(sites){copy=new ArrayList<>(sites);}addLog("TEST","测试全部，共 "+copy.size()+" 个站点");for(Site s:copy)test(s);}

    void toggle(){if(monitoring){monitoring=false;if(monitorTask!=null)monitorTask.cancel(false);monitor.setText("持续监测");status.setText("持续监测已停止");addLog("MONITOR","持续监测停止");return;}monitoring=true;round=0;monitor.setText("停止监测");status.setText("每 5 秒探测；每 30 秒测速和 DNS");addLog("MONITOR","持续监测启动：5 秒探测，30 秒完整测试");monitorTask=scheduler.scheduleAtFixedRate(()->{boolean full=round++%6==0;List<Site> copy; synchronized(sites){copy=new ArrayList<>(sites);}for(Site s:copy)pool.execute(()->{NetworkTester.Result r=NetworkTester.probe(this,s.url,full,full);stats.computeIfAbsent(s.id,k->new Stat()).apply(r);addProbeLog(s,r,full?"监测/完整":"监测");update(s.id);});},0,5,TimeUnit.SECONDS);}

    void refreshNetwork(){network.setText("当前网络\n连接: "+type()+"\n局域网 IP: "+localIp()+"\n系统 DNS: "+NetworkTester.describeDns(this)+"\n公网 IP: 获取中…");addLog("NETWORK","刷新网络："+type()+"；局域网IP="+localIp()+"；DNS="+NetworkTester.describeDns(this));pool.execute(()->{String ip=NetworkTester.publicIp();addLog("NETWORK","公网 IP="+(ip==null?"获取失败":ip));runOnUiThread(()->network.setText("当前网络\n连接: "+type()+"\n局域网 IP: "+localIp()+"\n系统 DNS: "+NetworkTester.describeDns(this)+"\n公网 IP: "+(ip==null?"获取失败":ip)));});}
    String type(){ConnectivityManager cm=getSystemService(ConnectivityManager.class);Network n=cm.getActiveNetwork();NetworkCapabilities c=n==null?null:cm.getNetworkCapabilities(n);if(c==null)return "无网络";if(c.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return "VPN";if(c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return "Wi‑Fi";if(c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))return "蜂窝数据";return "其他";}
    String localIp(){try{Enumeration<NetworkInterface> es=NetworkInterface.getNetworkInterfaces();while(es.hasMoreElements()){Enumeration<InetAddress> as=es.nextElement().getInetAddresses();while(as.hasMoreElements()){InetAddress a=as.nextElement();if(a instanceof Inet4Address&&!a.isLoopbackAddress())return a.getHostAddress();}}}catch(Exception ignored){}return "--";}

    void add(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(20),0,dp(20),0);EditText n=new EditText(this);n.setHint("名称，例如 OpenAI");EditText u=new EditText(this);u.setHint("https://openai.com");u.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);l.addView(n);l.addView(u);new AlertDialog.Builder(this).setTitle("添加检测网站").setView(l).setPositiveButton("添加",(d,w)->{String url=u.getText().toString().trim();if(!url.isEmpty()){if(!url.startsWith("http://")&&!url.startsWith("https://"))url="https://"+url;Site s=new Site(UUID.randomUUID().toString(),n.getText().toString().trim().isEmpty()?url:n.getText().toString().trim(),url);sites.add(s);stats.put(s.id,new Stat());save();addLog("SITE","添加站点 "+s.name+" "+s.url);render();}}).setNegativeButton("取消",null).show();}
    void remove(Site s){sites.removeIf(x->x.id.equals(s.id));stats.remove(s.id);save();addLog("SITE","删除站点 "+s.name+" "+s.url);render();}
    void load(){String raw=getSharedPreferences("netprobe",0).getString("sites",null);if(raw==null){sites.add(new Site("baidu","百度","https://www.baidu.com/"));sites.add(new Site("bilibili","哔哩哔哩","https://www.bilibili.com/"));sites.add(new Site("github","GitHub","https://github.com/"));sites.add(new Site("cf","Cloudflare","https://www.cloudflare.com/"));sites.add(new Site("google","Google","https://www.google.com/"));sites.add(new Site("youtube","YouTube","https://www.youtube.com/"));}else for(String line:raw.split("\n")){String[] p=line.split("\t",3);if(p.length==3)sites.add(new Site(p[0],p[1],p[2]));}for(Site s:sites)stats.put(s.id,new Stat());}
    void save(){StringBuilder b=new StringBuilder();synchronized(sites){for(Site s:sites){if(b.length()>0)b.append('\n');b.append(s.id).append('\t').append(s.name.replace('\t',' ')).append('\t').append(s.url.replace('\t',' '));}}getSharedPreferences("netprobe",0).edit().putString("sites",b.toString()).apply();}

    void addProbeLog(Site site,NetworkTester.Result r,String mode){StringBuilder b=new StringBuilder();b.append(site.name).append(" [").append(mode).append("] ");b.append(r.ok?"OK":"FAIL");if(r.httpCode!=null)b.append(" HTTP=").append(r.httpCode);if(r.ip!=null)b.append(" IP=").append(r.ip);if(r.dnsMeasured){b.append(" DNS=").append(r.dnsMs==null?"--":r.dnsMs+"ms");if(r.dnsServers!=null)b.append(" server=").append(r.dnsServers);if(r.dnsError!=null)b.append(" dnsErr=").append(r.dnsError);}if(r.latencyMs!=null)b.append(" latency=").append(r.latencyMs).append("ms");if(r.speedMbps!=null)b.append(" speed=").append(String.format(Locale.US,"%.2fMbps",r.speedMbps));if(r.bytes>0)b.append(" bytes=").append(r.bytes);if(r.error!=null)b.append(" err=").append(r.error);addLog(r.ok?"PROBE":"FAIL",b.toString());}
    void addLog(String type,String message){String line="["+new SimpleDateFormat("HH:mm:ss.SSS",Locale.getDefault()).format(new Date())+"]["+type+"] "+message;synchronized(logLock){logs.addLast(line);while(logs.size()>500)logs.removeFirst();}refreshLogView();}
    void refreshLogView(){runOnUiThread(()->{if(logView==null)return;StringBuilder b=new StringBuilder();synchronized(logLock){int skip=Math.max(0,logs.size()-8),i=0;for(String line:logs){if(i++<skip)continue;if(b.length()>0)b.append('\n');b.append(line);}}logView.setText(b.length()==0?"暂无日志":b.toString());});}
    String allLogs(){StringBuilder b=new StringBuilder();synchronized(logLock){for(String line:logs){if(b.length()>0)b.append('\n');b.append(line);}}return b.toString();}
    void copyLogs(){String text=allLogs();ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);cm.setPrimaryClip(ClipData.newPlainText("NetProbe logs",text));Toast.makeText(this,"已复制 "+logs.size()+" 条日志",Toast.LENGTH_SHORT).show();}
    void clearLogs(){synchronized(logLock){logs.clear();}refreshLogView();Toast.makeText(this,"日志已清空",Toast.LENGTH_SHORT).show();}
    String safe(String s){return s==null?"未知":s;}

    TextView t(String x,float z,int color,boolean bold){TextView v=new TextView(this);v.setText(x);v.setTextSize(z);v.setTextColor(color);if(bold)v.setTypeface(null,1);v.setPadding(0,dp(4),0,dp(4));return v;} LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setPadding(0,dp(8),0,0);return l;} LinearLayout.LayoutParams lp(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),1);p.setMargins(dp(4),0,dp(4),0);return p;} Button btn(String x,boolean primary){Button b=new Button(this);b.setText(x);b.setAllCaps(false);b.setTextColor(primary?BG:TEXT);b.setBackground(box(primary?ACC:CARD,primary?ACC:BORDER));return b;} GradientDrawable box(int fill,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setStroke(dp(1),stroke);g.setCornerRadius(dp(14));return g;} int dp(int v){return (int)(v*getResources().getDisplayMetrics().density);}
}
