package br.com.sistema51.a34;

import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.net.Uri;
import android.os.*;
import org.json.JSONArray;
import org.json.JSONObject;
import br.com.sistema51.a34.dsp.AudioProfile;
import br.com.sistema51.a34.io.OfflineLab;
import br.com.sistema51.a34.service.UsbAudioService;
import br.com.sistema51.a34.ui.AppBridge;
import br.com.sistema51.a34.usb.UsbAudioController;
import java.io.File;
import java.util.concurrent.*;

/** UI consumes cached state. Binder, USB topology and file work run separately. */
public final class AppFacade extends AppBridge {
    private final Context context;
    private final UsbAudioController usb;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(AppFacade::backgroundThread);
    private final ScheduledExecutorService monitor=Executors.newSingleThreadScheduledExecutor(AppFacade::backgroundThread);
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile UsbAudioService.LocalBinder binder;
    private volatile AudioProfile active;
    private volatile AudioProfile pendingLiveProfile;
    private volatile JSONObject activeJson;
    private volatile JSONObject cachedStatus;
    private volatile String lastReport="Nenhum teste executado.",lastError="";
    private volatile File lastOutput;
    private volatile UsbDevice selectedUsb;
    private volatile boolean busy,closed,topologyDirty=true;
    private volatile long statusAt;
    private JSONObject topology=new JSONObject();
    private long topologyAt;
    private boolean bound;
    private boolean retained;
    private long revision;
    private final br.com.sistema51.a34.service.OpticalAutoStartPolicy autoDolby;
    public boolean autoDolbyEnabled(){return autoDolby.enabled();}
    public boolean autoDolbyPaused(){return autoDolby.paused();}
    public synchronized void setAutoDolbyEnabled(boolean enabled){
        autoDolby.configure(enabled);
        context.getSharedPreferences("audio_automation_v1",Context.MODE_PRIVATE).edit().putBoolean("enabled",enabled).putBoolean("paused",false).apply();
    }
    public void retainForApplication(){retained=true;}
    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder service){binder=(UsbAudioService.LocalBinder)service;}
        @Override public void onServiceDisconnected(ComponentName name){binder=null;topologyDirty=true;}
    };
    private static Thread backgroundThread(Runnable job){return new Thread(()->{
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);job.run();
    },"A34-background");}
    public AppFacade(Context source){
        context=source.getApplicationContext();usb=new UsbAudioController(context);
        SharedPreferences automatic=context.getSharedPreferences("audio_automation_v1",Context.MODE_PRIVATE);
        autoDolby=new br.com.sistema51.a34.service.OpticalAutoStartPolicy(automatic.getBoolean("enabled",true),automatic.getBoolean("paused",false));
        try{activeJson=ProfileStore.loadJson(context);active=ProfileStore.fromJson(activeJson);}
        catch(Exception e){active=AudioProfile.defaultProfile();activeJson=ProfileStore.toJson(active,"Referência PC");lastError=e.getMessage();}
        cachedStatus=initialStatus();
        usb.setListener(new UsbAudioController.Listener(){
            @Override public void onUsbChanged(){topologyDirty=true;}
            @Override public void onUsbDetached(int id){UsbDevice selected=selectedUsb;if(selected!=null&&selected.getDeviceId()==id)selectedUsb=null;topologyDirty=true;}
        });
        bound=context.bindService(new Intent(context,UsbAudioService.class),connection,Context.BIND_AUTO_CREATE);
        monitor.scheduleWithFixedDelay(this::refreshStatus,0,400,TimeUnit.MILLISECONDS);
    }
    private JSONObject initialStatus(){try{return new JSONObject().put("state","loading").put("title","Lendo interface USB")
            .put("detail","Consultando a placa em segundo plano…").put("running",false).put("usbAttached",false)
            .put("hardwareReady",false).put("requiresRecordingPermission",false).put("meters",new JSONArray(new float[]{0,0,0,0,0,0}));}
        catch(Exception e){throw new IllegalStateException(e);}}
    @Override public synchronized JSONObject loadProfile(){try{return new JSONObject(activeJson.toString());}catch(Exception e){throw new IllegalStateException(e);}}
    public synchronized long profileRevision(){return revision;}
    public synchronized br.com.sistema51.a34.room.RoomPlayback.Ticket beginRoomTest(String id,int channel,long expectedRevision){
        if(revision!=expectedRevision)throw new java.util.ConcurrentModificationException("O perfil mudou. Atualize antes de medir.");
        UsbAudioService.LocalBinder current=binder;
        if(current==null)throw new IllegalStateException("Serviço de áudio indisponível.");
        return current.beginRoomTest(id,channel,active);
    }
    public synchronized void cancelRoomTest(){UsbAudioService.LocalBinder current=binder;if(current!=null)current.cancelRoomTest();}
    public synchronized void setRoomCorrection(br.com.sistema51.a34.dsp.RoomCorrection correction,long expectedRevision){
        if(expectedRevision!=revision)throw new java.util.ConcurrentModificationException("O perfil mudou. Confira antes de aplicar.");
        try{JSONObject json=loadProfile();json.put("roomCorrection",ProfileStore.roomToJson(correction));applyProfile(json);}
        catch(org.json.JSONException e){throw new IllegalStateException(e);}
    }
    public synchronized JSONObject patchProfile(JSONObject changes,long expectedRevision,boolean allowDelays){
        if(expectedRevision!=revision)throw new java.util.ConcurrentModificationException("O perfil mudou. Atualize o painel antes de aplicar.");
        try{
            JSONObject merged=loadProfile();java.util.Iterator<String> keys=changes.keys();
            while(keys.hasNext()){
                String key=keys.next();if(!merged.has(key)||"schemaVersion".equals(key)||"sampleRate".equals(key)||"effectiveLfeHeadroom".equals(key))throw new IllegalArgumentException("Parâmetro não editável: "+key);
                if("delaySamples".equals(key)&&!allowDelays)throw new IllegalArgumentException("Confirme o desbloqueio dos atrasos.");
                Object before=merged.get(key),after=changes.get(key);
                if(before instanceof Boolean&&!(after instanceof Boolean)||before instanceof Number&&!(after instanceof Number)
                    ||before instanceof JSONArray&&!(after instanceof JSONArray)||before instanceof JSONObject&&!(after instanceof JSONObject)
                    ||before instanceof String&&!(after instanceof String))throw new IllegalArgumentException("Tipo inválido: "+key);
                merged.put(key,after);
            }
            applyProfile(merged);return loadProfile();
        }catch(org.json.JSONException e){throw new IllegalArgumentException("Perfil inválido",e);}
    }
    @Override public synchronized void applyProfile(JSONObject json){
        if(closed)throw new IllegalStateException("Tela encerrada.");
        AudioProfile next=ProfileStore.fromJson(json);JSONObject normalized=ProfileStore.toJson(next,ProfileStore.checkedName(json.optString("name","Meu perfil")));
        if(normalized.toString().equals(activeJson.toString()))return;
        UsbAudioService.LocalBinder current=binder;
        if(current!=null&&current.isBusy()&&!current.statusJson().optBoolean("sourcePolicyAutomatic")
                &&next.getRequiredInputChannels()!=active.getRequiredInputChannels())throw new IllegalArgumentException("Pare o áudio PCM antes de trocar o modo de entrada.");
        ProfileStore.saveNormalizedAsync(context,normalized);active=next;activeJson=normalized;lastError="";
        revision++;
        if(current!=null&&current.isBusy()){
            pendingLiveProfile=next;
            // The binder only publishes an immutable profile; native USB I/O stays on its worker.
            if(current.updateProfile(next))pendingLiveProfile=null;
        }
    }
    @Override public void saveProfile(JSONObject json){applyProfile(json);}
    /** A bounded monitor owns all framework topology calls. */
    private void refreshStatus(){
        if(closed)return;
        try{
            long now=SystemClock.elapsedRealtime();
            UsbAudioService.LocalBinder current=binder;
            // Do not open a second USB connection or query AudioManager formats
            // while the exclusive USBFS session owns the interfaces. Detach
            // events already clear selectedUsb and stop the session separately.
            boolean audioBusy=current!=null&&current.isBusy();
            if(!audioBusy&&(topologyDirty||now-topologyAt>=3000)){
                topology=usb.snapshotJson();UsbDevice next=null;
                for(UsbDevice device:usb.listDevices())if(UsbAudioController.isAudioCandidate(device)){next=device;break;}
                selectedUsb=next;topologyAt=now;topologyDirty=false;
            }
            synchronized(this){
                AudioProfile pending=pendingLiveProfile;
                if(current!=null&&current.isRunning()&&pending!=null){
                    if(!current.updateProfile(pending))lastError="Perfil salvo; pare e reinicie a entrada USB para aplicá-lo.";
                    if(pendingLiveProfile==pending)pendingLiveProfile=null;
                }
            }
            JSONObject value=current==null?initialStatus():current.statusJson();
            UsbDevice selected=selectedUsb;
            boolean permission=false;JSONArray devices=topology.optJSONArray("usbDevices");
            if(devices!=null&&selected!=null)for(int i=0;i<devices.length();i++){
                JSONObject entry=devices.optJSONObject(i);if(entry!=null&&entry.optInt("deviceId")==selected.getDeviceId())permission=entry.optBoolean("permission");
            }
            value.put("usbAttached",selected!=null).put("usbDevices",topology).put("androidUsbAudioDevices",topology.optJSONArray("androidUsbAudioDevices"))
                    .put("usbSummary",selected==null?"Nenhuma interface USB de áudio conectada":selected.getProductName()==null?"Interface USB de áudio":selected.getProductName().trim())
                    .put("requiresRecordingPermission",false)
                    .put("requiresPcmRecordingPermission",selected!=null&&context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
                    .put("hardwareReady",selected!=null&&permission)
                    .put("opticalHardwareReady",selected!=null&&selected.getVendorId()==0x0d8c&&selected.getProductId()==0x0102&&permission)
                    .put("defaultAudioRoute","optical");
            if(!value.has("opticalAc3Ready"))value.put("opticalAc3Ready",false);
            if(!value.has("opticalDetail"))value.put("opticalDetail","Fire TV óptico: canais identificados na decodificação; mono/estéreo recebem upmix e 5.1 é preservado.");
            if(selected!=null&&"waiting_usb".equals(value.optString("state"))){
                value.put("state",permission?"ready":"usb_permission").put("title",permission?"Interface USB conectada":"Autorize a interface USB")
                        .put("detail",permission?"Inicie Fire TV óptico para decodificar e processar o áudio automaticamente.":"Solicite o acesso USB na aba Diagnóstico.");
            }
            value.put("autoDolbyEnabled",autoDolby.enabled()).put("autoDolbyPaused",autoDolby.paused());
            if(!closed){cachedStatus=value;statusAt=now;}
            synchronized(this){
                if(current!=null&&!closed&&autoDolby.shouldStart(now,value.optBoolean("opticalHardwareReady"),busy||current.isBusy(),value.optBoolean("signalReady"))){
                    try{startRoute("optical",true);}catch(RuntimeException failure){lastError="Início automático aguardando: "+failure.getMessage();}
                }
                if(autoDolby.paused()&&!context.getSharedPreferences("audio_automation_v1",Context.MODE_PRIVATE).getBoolean("paused",false))
                    context.getSharedPreferences("audio_automation_v1",Context.MODE_PRIVATE).edit().putBoolean("paused",true).apply();
            }
        }catch(Exception e){if(!closed){cachedStatus=errorSnapshot(e.getMessage());statusAt=SystemClock.elapsedRealtime();}}
    }
    /** Cache-only: no USB enumeration, Binder or disk I/O. */
    @Override public synchronized JSONObject snapshot(){
        try{
            // Nested telemetry is immutable after publication; copying its full JSON tree
            // on every frame creates unnecessary UI work and garbage collection pressure.
            JSONObject source=cachedStatus,value=new JSONObject();java.util.Iterator<String> keys=source.keys();
            while(keys.hasNext()){String key=keys.next();value.put(key,source.opt(key));}
            value.put("profile",activeJson).put("profileRevision",revision).put("busy",busy)
                    .put("autoDolbyEnabled",autoDolby.enabled()).put("autoDolbyPaused",autoDolby.paused())
                    .put("offlineReportAvailable",lastOutput!=null).put("lastReport",lastReport)
                    .put("statusAgeMs",statusAt==0?0:SystemClock.elapsedRealtime()-statusAt);
            if(!lastError.isEmpty())value.put("detail",lastError);
            if(busy)value.put("testStatus","Processando em segundo plano…");return value;
        }catch(Exception e){return errorSnapshot(e.getMessage());}
    }
    private static JSONObject errorSnapshot(String detail){try{return new JSONObject().put("state","error").put("title","Diagnóstico indisponível").put("detail",detail==null?"Falha de diagnóstico":detail).put("running",false);}catch(Exception e){throw new IllegalStateException(e);}}
    /** Fire TV is the primary live route. Starting never changes the saved gain or mute. */
    @Override public void start(){startRoute("optical");}
    public synchronized boolean startRoute(String route){
        return startRoute(route,false);
    }
    private synchronized boolean startRoute(String route,boolean automatic){
        if(!"optical".equals(route)&&!"pcm".equals(route))throw new IllegalArgumentException("Entrada deve ser optical ou pcm.");
        if(closed)throw new IllegalStateException("O controle está encerrado.");
        if(busy)throw new IllegalStateException("Aguarde a bancada terminar antes de iniciar o áudio.");
        UsbAudioService.LocalBinder current=binder;
        if(current!=null&&current.isBusy())throw new IllegalStateException("Pare e aguarde o encerramento da sessão atual antes de iniciar.");
        UsbDevice device=selectedUsb;
        if(statusAt==0||SystemClock.elapsedRealtime()-statusAt>5000)throw new IllegalStateException("Aguarde a atualização do diagnóstico USB.");
        if(device==null)throw new IllegalStateException("Aguardando a CM6206. Testes de arquivo continuam disponíveis.");
        if(!snapshot().optBoolean("hardwareReady"))throw new IllegalStateException("Autorize a interface no botão de acesso USB.");
        if("optical".equals(route)&&(device.getVendorId()!=0x0d8c||device.getProductId()!=0x0102))throw new IllegalStateException("A entrada óptica direta requer a CM6206 0d8c:0102 validada.");
        if("pcm".equals(route)&&context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("Permita a captura de áudio para iniciar o modo PCM USB.");
        lastError="";
        if(!automatic){autoDolby.resume();context.getSharedPreferences("audio_automation_v1",Context.MODE_PRIVATE).edit().putBoolean("paused",false).apply();}
        context.startForegroundService(new Intent(context,UsbAudioService.class)
                .setAction("optical".equals(route)?UsbAudioService.ACTION_START_OPTICAL:UsbAudioService.ACTION_START_PCM)
                .putExtra(UsbAudioService.EXTRA_USB_DEVICE_ID,device.getDeviceId()));
        return true;
    }
    @Override public synchronized void stop(){
        if(closed)return;lastError="";
        autoDolby.pause();context.getSharedPreferences("audio_automation_v1",Context.MODE_PRIVATE).edit().putBoolean("paused",true).apply();
        UsbAudioService.LocalBinder current=binder;if(current!=null)current.stopAudio();
        // Cancel the current worker immediately and a start Intent still awaiting delivery.
        context.startService(new Intent(context,UsbAudioService.class).setAction(UsbAudioService.ACTION_STOP_AUDIO));
    }
    @Override public void requestUsbPermission(){
        if(closed)return;UsbDevice device=selectedUsb;if(device==null){lastError="Nenhuma interface de áudio USB conectada.";return;}usb.requestPermission(device.getDeviceId());topologyDirty=true;
    }
    private interface Job{void run()throws Exception;}
    private void submit(Callback callback,Job job){
        synchronized(this){
            if(closed){callback.onError("Tela encerrada.");return;}
            if(busy){callback.onError("Aguarde o teste atual terminar.");return;}
            UsbAudioService.LocalBinder current=binder;
            if(current!=null&&current.isBusy()){callback.onError("Pare o áudio antes de executar a bancada de arquivos.");return;}
            busy=true;lastError="";
        }
        try{worker.submit(()->{
            String report=null,error=null;
            try{job.run();report=lastReport;}
            catch(Exception e){error=e.getMessage()==null?e.toString():e.getMessage();lastError=error;lastReport="Teste não concluído: "+error;}
            finally{busy=false;}
            final String completed=report,failed=error;
            main.post(()->{if(!closed){if(failed==null)callback.onComplete(completed);else callback.onError(failed);}});
        });}catch(RejectedExecutionException e){busy=false;if(!closed)callback.onError("A bancada está encerrando.");}
    }
    @Override public void selfTest(Callback callback){submit(callback,()->{SelfTests.Result r=SelfTests.run(context);lastReport=r.report.toString(2);lastOutput=r.output;});}
    @Override public void hardwareTest(Callback callback){submit(callback,()->{
        JSONObject report=br.com.sistema51.a34.usb.HardwareDiagnostics.collect(context);lastReport=report.toString(2);lastOutput=null;
    });}
    @Override public void testFile(Uri input,Callback callback){AudioProfile settings=active;submit(callback,()->{OfflineLab.Result r=OfflineLab.process(context,input,settings);lastReport=r.report.toString(2);lastOutput=r.output;});}
    @Override public String lastReport(){return lastReport;}
    @Override public Uri lastOutputUri(){File file=lastOutput;return file==null?null:Uri.fromFile(file);}
    @Override public void close(){
        if(retained)return;
        if(closed)return;closed=true;monitor.shutdownNow();worker.shutdownNow();usb.setListener(null);usb.close();
        if(bound){context.unbindService(connection);bound=false;}binder=null;main.removeCallbacksAndMessages(null);
    }
}
