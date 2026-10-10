package br.com.sistema51.controller;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** LAN companion: discovery and one restricted WebView; never owns audio hardware. */
public final class ControlActivity extends Activity {
    private static final String SERVICE_TYPE="_sistema51._tcp.";
    private static final int BACKGROUND=0xff0b1019,TEXT=0xffeaf2f5,MINT=0xff83e6c7;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final Set<String> resolved=new HashSet<>();
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private WifiManager.MulticastLock multicast;
    private LinearLayout root,home,devices,toolbar;
    private TextView message,address;
    private EditText manual;
    private WebView web;
    private SharedPreferences prefs;
    private volatile String endpoint="";
    private boolean destroyed,discovering;
    private int generation,discoveryEpoch;

    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(8),0,dp(8));return v;}
    private Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setTextColor(BACKGROUND);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(MINT));b.setAllCaps(false);b.setOnClickListener(v->action.run());return b;}
    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);prefs=getSharedPreferences("lan_controller",MODE_PRIVATE);
        nsd=getSystemService(NsdManager.class);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BACKGROUND);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            int top,bottom;
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());top=bars.top;bottom=bars.bottom;}
            else{top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();}
            v.setPadding(0,top,0,bottom);return insets;
        });
        setContentView(root);
        toolbar=new LinearLayout(this);toolbar.setPadding(dp(12),dp(4),dp(12),dp(4));toolbar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        toolbar.addView(button("Dispositivos",this::showHome));address=text("A34 DSP",12,MINT);address.setPadding(dp(12),0,0,0);toolbar.addView(address);toolbar.setVisibility(View.GONE);root.addView(toolbar);
        ScrollView scroll=new ScrollView(this);home=new LinearLayout(this);home.setOrientation(LinearLayout.VERTICAL);home.setPadding(dp(24),dp(24),dp(24),dp(24));scroll.addView(home);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        home.addView(text("CONTROLE 5.1",12,MINT));home.addView(text("Seu A34, ao alcance.",30,TEXT));home.addView(text("Volume, caixas e equalizador na mesma rede Wi-Fi. O processamento do áudio continua no A34.",16,0xffa2b0c2));
        home.addView(button("Buscar A34 na rede",this::startDiscovery));
        message=text("Procurando A34 DSP…",14,MINT);home.addView(message);
        devices=new LinearLayout(this);devices.setOrientation(LinearLayout.VERTICAL);home.addView(devices);
        home.addView(text("Conectar pelo endereço",18,TEXT));manual=new EditText(this);manual.setSingleLine(true);manual.setTextColor(TEXT);manual.setHintTextColor(0xff8996a6);manual.setHint("192.168.1.101:8051");manual.setText(prefs.getString("endpoint",""));manual.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);home.addView(manual);
        home.addView(button("Conectar",()->connect(manual.getText().toString(),null)));
        home.addView(text("No A34, ative Controle local em Diagnóstico. O código de quatro dígitos é pedido pelo painel ao conectar. Se a busca não encontrar o DSP, use o endereço mostrado no A34.",14,0xffa2b0c2));
        web=new WebView(this);web.setBackgroundColor(BACKGROUND);web.setVisibility(View.GONE);root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setSupportMultipleWindows(false);
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onJsConfirm(WebView view,String url,String prompt,JsResult result){
                if(!LocalEndpoint.sameOrigin(endpoint,url)){result.cancel();return true;}
                new android.app.AlertDialog.Builder(ControlActivity.this).setMessage(prompt).setPositiveButton("Confirmar",(d,w)->result.confirm()).setNegativeButton("Cancelar",(d,w)->result.cancel()).setOnCancelListener(d->result.cancel()).show();return true;
            }
        });
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return !LocalEndpoint.sameOrigin(endpoint,request.getUrl().toString());}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                if(LocalEndpoint.sameOrigin(endpoint,request.getUrl().toString()))return null;
                return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame())runOnUiThread(()->{showHome();message.setText("A34 indisponível. Confira o Wi-Fi e busque novamente.");});}
        });
        startDiscovery();
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0,()->{if(web.getVisibility()==View.VISIBLE)showHome();else finish();});
        String savedEndpoint=prefs.getString("endpoint","");if(!savedEndpoint.isEmpty())connect(savedEndpoint,prefs.getString("identity",null));
    }
    private void showHome(){generation++;web.stopLoading();web.setVisibility(View.GONE);toolbar.setVisibility(View.GONE);root.getChildAt(1).setVisibility(View.VISIBLE);web.onPause();}
    private void connect(String supplied,String expectedId){
        final String target;try{target=LocalEndpoint.parse(supplied);}catch(IllegalArgumentException invalid){message.setText(invalid.getMessage());return;}
        int requestGeneration=++generation;message.setText("Conectando ao A34…");
        network.execute(()->{
            try{
                HttpURLConnection connection=(HttpURLConnection)new URL(target+"/api/info").openConnection();
                connection.setConnectTimeout(2500);connection.setReadTimeout(2500);connection.setInstanceFollowRedirects(false);
                JSONObject info;
                try{
                    if(connection.getResponseCode()!=200)throw new IOException("Controle local indisponível.");
                    try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                        byte[] b=new byte[512];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>4096)throw new IOException("Resposta inválida.");}
                        info=new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
                    }
                }finally{connection.disconnect();}
                if(!"sistema51-a34".equals(info.optString("application"))||info.optInt("protocol")!=1||info.optString("id").isEmpty())throw new IOException("Este endereço não é o DSP do A34.");
                String identity=info.getString("id");
                if(expectedId!=null&&!expectedId.equals(identity))throw new IOException("Outro DSP usa este endereço. Escolha o dispositivo encontrado na busca.");
                main.post(()->{
                    if(destroyed||requestGeneration!=generation)return;
                    endpoint=target;prefs.edit().putString("endpoint",target).putString("identity",identity).apply();manual.setText(target);address.setText("A34 DSP · "+target.substring(7));
                    stopDiscovery();root.getChildAt(1).setVisibility(View.GONE);toolbar.setVisibility(View.VISIBLE);web.setVisibility(View.VISIBLE);web.onResume();web.loadUrl(target+"/");
                });
            }catch(Exception failure){main.post(()->{if(!destroyed&&requestGeneration==generation){message.setText(failure.getMessage()==null?"Não foi possível conectar. Busque o A34 novamente.":failure.getMessage());startDiscovery();}});}
        });
    }
    private void startDiscovery(){
        if(destroyed||discovering||nsd==null)return;
        final int epoch=++discoveryEpoch;
        devices.removeAllViews();resolved.clear();message.setText("Procurando A34 DSP na rede…");
        WifiManager wifi=(WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if(wifi!=null){multicast=wifi.createMulticastLock("sistema51-discovery");multicast.setReferenceCounted(false);multicast.acquire();}
        discovery=new NsdManager.DiscoveryListener(){
            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onStartDiscoveryFailed(String type,int error){runOnUiThread(()->{if(epoch!=discoveryEpoch)return;stopDiscovery();message.setText("Busca indisponível. Use o endereço mostrado no A34.");});}
            @Override public void onStopDiscoveryFailed(String type,int error) { }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onServiceLost(NsdServiceInfo service) { }
            @Override public void onServiceFound(NsdServiceInfo service){
                nsd.resolveService(service,new NsdManager.ResolveListener(){
                    @Override public void onResolveFailed(NsdServiceInfo s,int error) { }
                    @Override public void onServiceResolved(NsdServiceInfo s){
                        InetAddress ip=s.getHost();
                        if(Build.VERSION.SDK_INT>=34)for(InetAddress candidate:s.getHostAddresses())if(candidate instanceof Inet4Address){ip=candidate;break;}
                        if(!(ip instanceof Inet4Address))return;
                        final String target;try{target=LocalEndpoint.parse("http://"+ip.getHostAddress()+":"+s.getPort());}catch(IllegalArgumentException ignored){return;}
                        runOnUiThread(()->{if(destroyed||!discovering||epoch!=discoveryEpoch||!resolved.add(target))return;message.setText("A34 encontrado. Toque para controlar.");devices.addView(button(s.getServiceName()+" · "+target.substring(7),()->connect(target,null)));});
                    }
                });
            }
        };
        try{discovering=true;nsd.discoverServices(SERVICE_TYPE,NsdManager.PROTOCOL_DNS_SD,discovery);}
        catch(RuntimeException failure){stopDiscovery();message.setText("Busca indisponível. Conecte pelo endereço do A34.");}
        main.postDelayed(()->{if(!destroyed&&discovering&&epoch==discoveryEpoch){stopDiscovery();if(resolved.isEmpty())message.setText("Nenhum A34 encontrado. Confira Controle local e o Wi-Fi, ou informe o endereço.");}},12000);
    }
    private void stopDiscovery(){if(discovering){discovering=false;try{nsd.stopServiceDiscovery(discovery);}catch(IllegalArgumentException ignored){}}if(multicast!=null&&multicast.isHeld())multicast.release();}
    @Override protected void onStart(){super.onStart();if(web!=null){web.resumeTimers();if(web.getVisibility()==View.VISIBLE)web.onResume();}}
    @Override protected void onStop(){stopDiscovery();if(web!=null){web.onPause();web.pauseTimers();}super.onStop();}
    // API 33+ uses the dispatcher callback above; this fallback serves API 28..32.
    @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){if(web.getVisibility()==View.VISIBLE)showHome();else super.onBackPressed();}
    @Override protected void onDestroy(){destroyed=true;generation++;stopDiscovery();main.removeCallbacksAndMessages(null);network.shutdownNow();if(web!=null){web.stopLoading();web.destroy();}super.onDestroy();}
}
