package br.com.sistema51.a34.remote;

import android.app.*;
import android.content.*;
import android.os.*;
import android.util.Base64;
import br.com.sistema51.a34.A34Application;
import br.com.sistema51.a34.AppFacade;
import br.com.sistema51.a34.ui.MainActivity;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Opt-in foreground LAN control, independent from the audio callback and ADB. */
public final class RemoteControlService extends Service {
    public static final int PORT=8051;
    public static final String STOP="br.com.sistema51.a34.REMOTE_STOP";
    private LocalHttpServer server;
    private String token;
    private AccessCodeGate accessCodeGate;
    private AppFacade facade;
    private LanDiscoveryAdvertiser discovery;
    private String discoveryIdentity;
    private final String session=UUID.randomUUID().toString();
    private static volatile String state="Desligado";
    public static String state(){return state;}
    public static void stop(Context context){context.getSharedPreferences("lan_control_v1",MODE_PRIVATE).edit().putBoolean("enabled",false).apply();context.stopService(new Intent(context,RemoteControlService.class));}
    public static void restoreIfEnabled(Context context){if(context.getSharedPreferences("lan_control_v1",MODE_PRIVATE).getBoolean("enabled",false))context.startForegroundService(new Intent(context,RemoteControlService.class));}
    public static String token(Context context){
        SharedPreferences prefs=context.getSharedPreferences("lan_control_v1",MODE_PRIVATE);
        String value=prefs.getString("token",null);
        if(value==null){byte[] bytes=new byte[24];new SecureRandom().nextBytes(bytes);value=Base64.encodeToString(bytes,Base64.NO_WRAP|Base64.URL_SAFE|Base64.NO_PADDING);if(!prefs.edit().putString("token",value).commit())throw new IllegalStateException("Chave não salva");}
        return value;
    }
    public static String accessCode(Context context){
        SharedPreferences prefs=context.getSharedPreferences("lan_control_v1",MODE_PRIVATE);
        String value=prefs.getString("access_code",null);
        if(value==null||!value.matches("[0-9]{4}")){
            value=String.format(Locale.ROOT,"%04d",new SecureRandom().nextInt(10_000));
            if(!prefs.edit().putString("access_code",value).commit())throw new IllegalStateException("Código local não salvo");
        }
        return value;
    }
    public static String addresses(){
        List<String> urls=new ArrayList<>();
        try{Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();while(interfaces.hasMoreElements()){
            NetworkInterface net=interfaces.nextElement();if(!net.isUp())continue;
            Enumeration<InetAddress> addresses=net.getInetAddresses();while(addresses.hasMoreElements()){
                InetAddress ip=addresses.nextElement();if(ip instanceof Inet4Address&&ip.isSiteLocalAddress()&&!ip.isLoopbackAddress())urls.add("http://"+ip.getHostAddress()+":"+PORT);
            }
        }}catch(SocketException ignored){}
        return urls.isEmpty()?"Conecte o A34 ao Wi-Fi local.":String.join("\n",urls);
    }
    @Override public void onCreate(){
        super.onCreate();token=token(this);SharedPreferences prefs=getSharedPreferences("lan_control_v1",MODE_PRIVATE);
        discoveryIdentity=prefs.getString("discovery_id",null);
        if(discoveryIdentity==null){discoveryIdentity=UUID.randomUUID().toString();prefs.edit().putString("discovery_id",discoveryIdentity).apply();}
        accessCodeGate=new AccessCodeGate(accessCode(this),prefs.getInt("access_failures",0),prefs.getInt("access_lockout_level",0),prefs.getLong("access_blocked_until",0));
        facade=((A34Application)getApplication()).facade();
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("a34_lan","Controle pela rede local",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,RemoteControlService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,"a34_lan").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Sistema 5.1 · Controle local ativo").setContentText("Porta "+PORT+" · código de 4 dígitos em Diagnóstico")
            .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Desligar controle",stop).build()).build();
        startForeground(52,notification);
        getSharedPreferences("lan_control_v1",MODE_PRIVATE).edit().putBoolean("enabled",true).apply();
        try{server=new LocalHttpServer(PORT,this::handle);state="Ativo";discovery=new LanDiscoveryAdvertiser(this,PORT,discoveryIdentity);}
        catch(IOException e){state="Falha ao abrir a porta "+PORT;stopSelf();}
    }
    private LocalHttpServer.Response json(int code,JSONObject value){return new LocalHttpServer.Response(code,"application/json",value.toString());}
    private JSONObject snapshot()throws org.json.JSONException{return facade.snapshot().put("controlSession",session);}
    private LocalHttpServer.Response asset(String name,String type)throws IOException{
        try(InputStream in=getAssets().open("remote/"+name);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] block=new byte[4096];int n;while((n=in.read(block))!=-1)out.write(block,0,n);
            return new LocalHttpServer.Response(200,type,new String(out.toByteArray(),StandardCharsets.UTF_8));
        }
    }
    private synchronized AccessCodeGate.Result unlock(String candidate){
        AccessCodeGate.Result result=accessCodeGate.unlock(candidate,System.currentTimeMillis());
        boolean saved=getSharedPreferences("lan_control_v1",MODE_PRIVATE).edit()
            .putInt("access_failures",accessCodeGate.failedAttempts())
            .putInt("access_lockout_level",accessCodeGate.lockoutLevel())
            .putLong("access_blocked_until",accessCodeGate.blockedUntilMillis()).commit();
        if(!saved)return new AccessCodeGate.Result(503,null,0);
        return result;
    }
    private LocalHttpServer.Response unlock(LocalHttpServer.Request request)throws Exception{
        if(!request.headers.getOrDefault("content-type","").startsWith("application/json"))
            return json(415,new JSONObject().put("error","Envie o código em JSON."));
        JSONObject body=new JSONObject(request.body);
        AccessCodeGate.Result result=unlock(body.optString("code",""));
        if(result.status==200)return json(200,new JSONObject().put("sessionToken",result.sessionToken)
            .put("expiresInSeconds",result.retryAfterMillis/1000));
        if(result.status==429)return json(429,new JSONObject().put("error","Muitas tentativas. Aguarde antes de tentar novamente.")
            .put("retryAfterSeconds",Math.max(1,(result.retryAfterMillis+999)/1000)));
        if(result.status==503)return json(503,new JSONObject().put("error","Não foi possível salvar a proteção do controle local."));
        return json(401,new JSONObject().put("error","Código de acesso incorreto."));
    }
    private boolean authorized(String supplied){
        byte[] expected=("Bearer "+token).getBytes(StandardCharsets.UTF_8);
        byte[] actual=supplied.getBytes(StandardCharsets.UTF_8);
        if(MessageDigest.isEqual(expected,actual))return true;
        return supplied.startsWith("Bearer ")&&accessCodeGate.authorizes(supplied.substring(7),System.currentTimeMillis());
    }
    private LocalHttpServer.Response handle(LocalHttpServer.Request request)throws Exception{
        if("GET".equals(request.method)){
            if("/api/info".equals(request.path))return json(200,new JSONObject().put("application","sistema51-a34")
                .put("name","A34 DSP").put("protocol",1).put("id",discoveryIdentity));
            if("/".equals(request.path))return asset("index.html","text/html");
            if("/app.js".equals(request.path))return asset("app.js","application/javascript");
            if("/style.css".equals(request.path))return asset("style.css","text/css");
        }
        if("POST".equals(request.method)&&"/api/unlock".equals(request.path)){
            try{return unlock(request);}catch(org.json.JSONException e){return json(400,new JSONObject().put("error","Código inválido."));}
        }
        String supplied=request.headers.getOrDefault("authorization","");
        if(!authorized(supplied))return json(401,new JSONObject().put("error","Sessão expirada. Informe novamente o código de acesso."));
        try{
            if("GET".equals(request.method)&&"/api/state".equals(request.path))return json(200,snapshot());
            if("POST".equals(request.method)){
                if(!request.headers.getOrDefault("content-type","").startsWith("application/json"))return json(415,new JSONObject().put("error","Envie JSON."));
                JSONObject body=new JSONObject(request.body);
                if(!session.equals(body.optString("session")))return json(409,new JSONObject().put("error","O controle foi reiniciado. Atualize o painel antes de aplicar."));
                if("/api/profile".equals(request.path)){
                    facade.patchProfile(body.getJSONObject("changes"),body.getLong("revision"),body.optBoolean("unlockDelays",false));
                    return json(200,snapshot());
                }
                if("/api/stop".equals(request.path)){facade.stop();return json(202,snapshot());}
                if("/api/automation".equals(request.path)){
                    if(!(body.opt("enabled") instanceof Boolean))throw new IllegalArgumentException("enabled deve ser booleano.");
                    facade.setAutoDolbyEnabled(body.getBoolean("enabled"));return json(200,snapshot());
                }
                if("/api/start".equals(request.path)){
                    if(!Boolean.TRUE.equals(body.opt("confirmPlayback")))throw new IllegalArgumentException("Confirme iniciar a captura e reprodução USB.");
                    Object route=body.opt("route");
                    if(route!=null&&!(route instanceof String))throw new IllegalArgumentException("Entrada deve ser optical ou pcm.");
                    facade.startRoute(route==null?"optical":(String)route);
                    return json(202,snapshot());
                }
            }
            return json(404,new JSONObject().put("error","Operação não encontrada."));
        }catch(java.util.ConcurrentModificationException e){return json(409,new JSONObject().put("error",e.getMessage()));}
        catch(IllegalArgumentException|org.json.JSONException e){return json(400,new JSONObject().put("error",e.getMessage()));}
        catch(SecurityException e){return json(409,new JSONObject().put("error","Abra o app no A34 e confira as permissões. "+e.getMessage()));}
        catch(IllegalStateException e){return json(409,new JSONObject().put("error",e.getMessage()==null?"O controle de áudio está indisponível.":e.getMessage()));}
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){if(intent!=null&&STOP.equals(intent.getAction())){getSharedPreferences("lan_control_v1",MODE_PRIVATE).edit().putBoolean("enabled",false).apply();stopSelf();}return START_NOT_STICKY;}
    @Override public void onDestroy(){if(discovery!=null)discovery.close();if(server!=null)server.close();if("Ativo".equals(state))state="Desligado";super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
