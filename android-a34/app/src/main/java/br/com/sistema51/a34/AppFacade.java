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
    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder service){binder=(UsbAudioService.LocalBinder)service;}
        @Override public void onServiceDisconnected(ComponentName name){binder=null;topologyDirty=true;}
    };
    private static Thread backgroundThread(Runnable job){return new Thread(()->{
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);job.run();
    },"A34-background");}
    public AppFacade(Context source){
        context=source.getApplicationContext();usb=new UsbAudioController(context);
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
    @Override public JSONObject loadProfile(){try{return new JSONObject(activeJson.toString());}catch(Exception e){throw new IllegalStateException(e);}}
    @Override public synchronized void applyProfile(JSONObject json){
        if(closed)throw new IllegalStateException("Tela encerrada.");
        AudioProfile next=ProfileStore.fromJson(json);JSONObject normalized=ProfileStore.toJson(next,ProfileStore.checkedName(json.optString("name","Meu perfil")));
        if(normalized.toString().equals(activeJson.toString()))return;
        UsbAudioService.LocalBinder current=binder;
        if(current!=null&&current.isBusy()&&next.getRequiredInputChannels()!=active.getRequiredInputChannels())throw new IllegalArgumentException("Pare o áudio antes de trocar o modo de entrada.");
        ProfileStore.saveNormalizedAsync(context,normalized);active=next;activeJson=normalized;lastError="";
        if(current!=null&&current.isRunning())pendingLiveProfile=next;
    }
    @Override public void saveProfile(JSONObject json){applyProfile(json);}
    /** A bounded monitor owns all framework topology calls. */
    private void refreshStatus(){
        if(closed)return;
        try{
            long now=SystemClock.elapsedRealtime();
            if(topologyDirty||now-topologyAt>=3000){
                topology=usb.snapshotJson();UsbDevice next=null;
                for(UsbDevice device:usb.listDevices())if(UsbAudioController.isAudioCandidate(device)){next=device;break;}
                selectedUsb=next;topologyAt=now;topologyDirty=false;
            }
            UsbAudioService.LocalBinder current=binder;
            AudioProfile pending=pendingLiveProfile;
            if(current!=null&&current.isRunning()&&pending!=null){
                if(!current.updateProfile(pending))lastError="Perfil salvo; pare e reinicie a entrada USB para aplicá-lo.";
                if(pendingLiveProfile==pending)pendingLiveProfile=null;
            }
            JSONObject value=current==null?initialStatus():current.statusJson();
            UsbDevice selected=selectedUsb;
            boolean permission=false;JSONArray devices=topology.optJSONArray("usbDevices");
            if(devices!=null&&selected!=null)for(int i=0;i<devices.length();i++){
                JSONObject entry=devices.optJSONObject(i);if(entry!=null&&entry.optInt("deviceId")==selected.getDeviceId())permission=entry.optBoolean("permission");
            }
            value.put("usbAttached",selected!=null).put("usbDevices",topology).put("androidUsbAudioDevices",topology.optJSONArray("androidUsbAudioDevices"))
                    .put("usbSummary",selected==null?"Nenhuma interface USB de áudio conectada":selected.getProductName()==null?"Interface USB de áudio":selected.getProductName().trim())
                    .put("requiresRecordingPermission",selected!=null&&context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
                    .put("hardwareReady",selected!=null&&permission).put("opticalAc3Ready",false)
                    .put("opticalDetail","Captura óptica AC-3 direta depende da validação do transporte da CM6206.");
            if(selected!=null&&"waiting_usb".equals(value.optString("state"))){
                value.put("state",permission?"ready":"usb_permission").put("title",permission?"Interface USB conectada":"Autorize a interface USB")
                        .put("detail",permission?"Escolha o modo PCM adequado à entrada antes de iniciar.":"Solicite o acesso USB na aba Diagnóstico.");
            }
            if(!closed){cachedStatus=value;statusAt=now;}
        }catch(Exception e){if(!closed){cachedStatus=errorSnapshot(e.getMessage());statusAt=SystemClock.elapsedRealtime();}}
    }
    /** Cache-only: no USB enumeration, Binder or disk I/O. */
    @Override public JSONObject snapshot(){
        try{
            // Nested telemetry is immutable after publication; copying its full JSON tree
            // on every frame creates unnecessary UI work and garbage collection pressure.
            JSONObject source=cachedStatus,value=new JSONObject();java.util.Iterator<String> keys=source.keys();
            while(keys.hasNext()){String key=keys.next();value.put(key,source.opt(key));}
            value.put("profile",activeJson).put("busy",busy)
                    .put("offlineReportAvailable",lastOutput!=null).put("lastReport",lastReport)
                    .put("statusAgeMs",statusAt==0?0:SystemClock.elapsedRealtime()-statusAt);
            if(!lastError.isEmpty())value.put("detail",lastError);
            if(busy)value.put("testStatus","Processando em segundo plano…");return value;
        }catch(Exception e){return errorSnapshot(e.getMessage());}
    }
    private static JSONObject errorSnapshot(String detail){try{return new JSONObject().put("state","error").put("title","Diagnóstico indisponível").put("detail",detail==null?"Falha de diagnóstico":detail).put("running",false);}catch(Exception e){throw new IllegalStateException(e);}}
    @Override public void start(){
        if(closed)return;if(busy){lastError="Aguarde a bancada terminar antes de iniciar o áudio.";return;}UsbDevice device=selectedUsb;
        if(statusAt==0||SystemClock.elapsedRealtime()-statusAt>5000){lastError="Aguarde a atualização do diagnóstico USB.";return;}
        if(device==null){lastError="Aguardando a CM6206. Testes de arquivo continuam disponíveis.";return;}
        if(!snapshot().optBoolean("hardwareReady")){lastError="Autorize a interface no botão de acesso USB.";return;}
        if(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){lastError="Permita a captura de áudio para iniciar o modo PCM USB.";return;}
        lastError="";context.startForegroundService(new Intent(context,UsbAudioService.class).setAction(UsbAudioService.ACTION_START_PCM).putExtra(UsbAudioService.EXTRA_USB_DEVICE_ID,device.getDeviceId()));
    }
    @Override public void stop(){if(closed)return;lastError="";UsbAudioService.LocalBinder current=binder;if(current!=null)current.stopAudio();else context.startService(new Intent(context,UsbAudioService.class).setAction(UsbAudioService.ACTION_STOP_AUDIO));}
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
        if(closed)return;closed=true;monitor.shutdownNow();worker.shutdownNow();usb.setListener(null);usb.close();
        if(bound){context.unbindService(connection);bound=false;}binder=null;main.removeCallbacksAndMessages(null);
    }
}
