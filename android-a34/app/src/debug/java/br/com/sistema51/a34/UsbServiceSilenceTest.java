package br.com.sistema51.a34;

import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.os.IBinder;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import br.com.sistema51.a34.dsp.AudioProfile;
import br.com.sistema51.a34.service.UsbAudioService;
import br.com.sistema51.a34.usb.UsbAudioController;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Bounded production-service test. Temporary profile is muted and restored afterwards. */
public final class UsbServiceSilenceTest {
    private UsbServiceSilenceTest(){}
    public static JSONObject run(Context context)throws Exception{return run(context,3500,null);}
    public static JSONObject run(Context context,long durationMs,android.app.Instrumentation instrumentation)throws Exception{
        if(durationMs<1000||durationMs>30000)throw new IllegalArgumentException("Duração de teste fora de 1 a 30 segundos.");
        if(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("Autorize captura no aplicativo antes do teste.");
        UsbAudioController usb=new UsbAudioController(context);UsbDevice selected=null;
        for(UsbDevice device:usb.listDevices())if(device.getVendorId()==0x0d8c&&device.getProductId()==0x0102&&usb.hasPermission(device.getDeviceId()))selected=device;
        if(selected==null){usb.close();throw new IllegalStateException("CM6206 ausente ou sem autorização USB.");}
        final UsbAudioService.LocalBinder[] binder=new UsbAudioService.LocalBinder[1];CountDownLatch connected=new CountDownLatch(1);
        ServiceConnection connection=new ServiceConnection(){
            @Override public void onServiceConnected(ComponentName name,IBinder service){binder[0]=(UsbAudioService.LocalBinder)service;connected.countDown();}
            @Override public void onServiceDisconnected(ComponentName name){binder[0]=null;}
        };
        SharedPreferences preferences=context.getSharedPreferences("audio_profiles_v1",Context.MODE_PRIVATE);String previous=preferences.getString("active",null);boolean bound=false;
        AppFacade facade=null;JSONObject result=null;long snapshotTotalNs=0,snapshotMaxNs=0;int snapshotCount=0;
        try{
            AudioProfile test=AudioProfile.defaultProfile().toBuilder().inputMode(AudioProfile.InputMode.STEREO_UPMIX).muted(true).masterGain(0).build();
            ProfileStore.save(context,test);
            bound=context.bindService(new Intent(context,UsbAudioService.class),connection,Context.BIND_AUTO_CREATE);
            if(!bound||!connected.await(3,TimeUnit.SECONDS)||binder[0]==null)throw new IllegalStateException("Serviço não conectou.");
            facade=new AppFacade(context);
            context.startForegroundService(new Intent(context,UsbAudioService.class).setAction(UsbAudioService.ACTION_START_PCM).putExtra(UsbAudioService.EXTRA_USB_DEVICE_ID,selected.getDeviceId()));
            JSONArray observations=new JSONArray();long begin=SystemClock.elapsedRealtime();JSONObject last=null;
            while(SystemClock.elapsedRealtime()-begin<durationMs){
                SystemClock.sleep(250);if(binder[0]==null)throw new IllegalStateException("Serviço desconectou.");last=binder[0].statusJson();observations.put(last);
                final AppFacade currentFacade=facade;final long[] measured=new long[1];
                Runnable snapshotJob=()->{long start=System.nanoTime();currentFacade.snapshot();measured[0]=System.nanoTime()-start;};
                if(instrumentation!=null)instrumentation.runOnMainSync(snapshotJob);else snapshotJob.run();
                snapshotCount++;snapshotTotalNs+=measured[0];snapshotMaxNs=Math.max(snapshotMaxNs,measured[0]);
            }
            boolean progress=last!=null&&last.optBoolean("running")&&last.optLong("capturedFrames")>0&&last.optLong("reproducedFrames")>0&&"running".equals(last.optString("state"));
            result=new JSONObject().put("ok",progress).put("kind","production_usb_silent_duplex").put("onlySilentOutput",true)
                    .put("durationMs",SystemClock.elapsedRealtime()-begin).put("lastStatus",last).put("observations",observations)
                    .put("uiSnapshotCalls",snapshotCount).put("uiSnapshotMeanMs",snapshotCount==0?0:snapshotTotalNs/(double)snapshotCount/1000000.0).put("uiSnapshotMaxMs",snapshotMaxNs/1000000.0)
                    .put("scope","Production foreground service with actual USB capture and six-slot playback; profile muted, output zero. USB capture source not proven optical, and no AC-3, connector mapping or acoustic latency validation.");
            return result;
        }finally{
            if(binder[0]!=null){
                long beginStop=System.nanoTime();binder[0].stopAudio();double stopCallMs=(System.nanoTime()-beginStop)/1000000.0;
                long deadline=SystemClock.elapsedRealtime()+2500;while(binder[0]!=null&&binder[0].isBusy()&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(10);
                if(result!=null)result.put("stopCallMs",stopCallMs).put("stopCompleted",binder[0]!=null&&!binder[0].isBusy()).put("stopTotalMs",(System.nanoTime()-beginStop)/1000000.0);
            }
            if(facade!=null)facade.close();if(bound)context.unbindService(connection);
            SharedPreferences.Editor edit=preferences.edit();if(previous==null)edit.remove("active");else edit.putString("active",previous);
            if(!edit.commit())throw new IllegalStateException("Perfil anterior não foi restaurado.");if(result!=null)result.put("profileRestored",true);usb.close();
        }
    }
}
