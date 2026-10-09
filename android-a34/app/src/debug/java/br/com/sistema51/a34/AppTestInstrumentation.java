package br.com.sistema51.a34;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import org.json.JSONObject;
import br.com.sistema51.a34.dsp.AudioProfile;
import br.com.sistema51.a34.io.OfflineLab;
import br.com.sistema51.a34.io.PlatformAc3Decoder;
import br.com.sistema51.a34.io.WavIO;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Debug-only app-UID tests; hardware capture/playback runs only in explicit modes. */
public final class AppTestInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);this.arguments=arguments;start();}
    @Override public void onStart(){
        Bundle result=new Bundle();Context context=getTargetContext();JSONObject report=new JSONObject();
        try{
            if(arguments!=null&&"capture-source".equals(arguments.getString("hardware"))){
                android.hardware.usb.UsbManager manager=(android.hardware.usb.UsbManager)context.getSystemService(Context.USB_SERVICE);
                int deviceId=-1;
                for(android.hardware.usb.UsbDevice device:manager.getDeviceList().values()){
                    if(device.getVendorId()==0x0d8c&&device.getProductId()==0x0102){
                        if(deviceId!=-1)throw new IllegalStateException("Mais de uma CM6206; rota ambígua.");
                        deviceId=device.getDeviceId();
                    }
                }
                report=UacCaptureSourceProbe.readSnapshot(context,deviceId);
                File hardwareDir=new File(context.getFilesDir(),"hardware");if(!hardwareDir.isDirectory()&&!hardwareDir.mkdirs())throw new IOException("Diagnóstico sem diretório.");
                write(new File(hardwareDir,"capture-source.json"),report.toString(2));
                result.putString("report",report.toString());finish(report.optBoolean("ok",false)?0:1,result);return;
            }
            if(arguments!=null&&"capture".equals(arguments.getString("hardware"))){
                File hardwareDir=new File(context.getFilesDir(),"hardware");
                report=UsbCaptureProbe.capture(context,hardwareDir);
                write(new File(hardwareDir,"capture-report.json"),report.toString(2));
                result.putString("report",report.toString());finish(report.optBoolean("ok",false)?0:1,result);return;
            }
            if(arguments!=null&&"silent-duplex".equals(arguments.getString("hardware"))){
                long duration=Long.parseLong(arguments.getString("duration-ms","3500"));
                report=UsbServiceSilenceTest.run(context,duration,this);
                File hardwareDir=new File(context.getFilesDir(),"hardware");if(!hardwareDir.isDirectory()&&!hardwareDir.mkdirs())throw new IOException("Diagnóstico sem diretório.");
                write(new File(hardwareDir,"silent-duplex.json"),report.toString(2));result.putString("report",report.toString());finish(report.getBoolean("ok")?0:1,result);return;
            }
            if(arguments!=null&&"silent-playback".equals(arguments.getString("hardware"))){
                report=br.com.sistema51.a34.usb.UsbSilentPlaybackProbe.run(context);
                File hardwareDir=new File(context.getFilesDir(),"hardware");if(!hardwareDir.isDirectory()&&!hardwareDir.mkdirs())throw new IOException("Diagnóstico sem diretório.");
                write(new File(hardwareDir,"silent-playback.json"),report.toString(2));result.putString("report",report.toString());finish(report.getBoolean("ok")?0:1,result);return;
            }
            if(arguments!=null&&"snapshot".equals(arguments.getString("hardware"))){
                report=br.com.sistema51.a34.usb.HardwareDiagnostics.collect(context);
                File hardwareDir=new File(context.getFilesDir(),"hardware");if(!hardwareDir.isDirectory()&&!hardwareDir.mkdirs())throw new IOException("Diagnóstico sem diretório.");
                write(new File(hardwareDir,"report.json"),report.toString(2));result.putString("report",report.toString());finish(0,result);return;
            }
            File dir=new File(context.getFilesDir(),"instrumentation");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Teste sem diretório.");
            SelfTests.Result self=SelfTests.run(context);report.put("selfTest",self.report);
            try(WavIO.Reader reader=new WavIO.Reader(self.output)){require(reader.channels==6&&reader.frames==48000,"WAV self test");}
            SharedPreferences preferences=context.getSharedPreferences("audio_profiles_v1",Context.MODE_PRIVATE);
            String before=preferences.getString("active",null);
            try{
                AudioProfile changed=AudioProfile.defaultProfile().toBuilder().masterGain(.123f)
                        .upmixDifference(.65f).upmixCenterGain(.8f).upmixBassCutoffHz(90).build();
                ProfileStore.saveJson(context,ProfileStore.toJson(changed,"Persistência"));
                require(ProfileStore.load(context).getMasterGain()==.123f,"Persistent profile");
                AudioProfile loaded=ProfileStore.load(context);
                require(loaded.getUpmixDifference()==.65f&&loaded.getUpmixCenterGain()==.8f&&loaded.getUpmixBassCutoffHz()==90,"Persistent upmix");
                report.put("upmixPersistence",true);
                boolean rejected=false;try{ProfileStore.fromJson(new JSONObject("{\"masterGain\":2}"));}catch(IllegalArgumentException expected){rejected=true;}
                require(rejected,"Invalid profile rejected");report.put("profilePersistence",true).put("invalidProfileRejected",true);
            }finally{SharedPreferences.Editor edit=preferences.edit();if(before==null)edit.remove("active");else edit.putString("active",before);require(edit.commit(),"Restore profile");}
            File broken=new File(dir,"truncated.wav");try(RandomAccessFile f=new RandomAccessFile(broken,"rw");InputStream in=new FileInputStream(self.output)){byte[] b=new byte[100];int n=in.read(b);f.setLength(0);f.write(b,0,n);}
            boolean rejected=false;try(WavIO.Reader ignored=new WavIO.Reader(broken)){}catch(IOException expected){rejected=true;}require(rejected,"Truncated WAV rejected");report.put("truncatedWavRejected",true);
            File ac3=new File(dir,"tones.ac3"),decoded=new File(dir,"decoded.wav"),processed=new File(dir,"processed.wav");
            try(InputStream in=context.getAssets().open("fixtures/tones-51.ac3");OutputStream out=new FileOutputStream(ac3)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
            JSONObject decoder=PlatformAc3Decoder.decode(ac3,decoded);require(decoder.getInt("channels")==6,"AC3 six channels");report.put("decoder",decoder);
            JSONObject pipeline=OfflineLab.processWav(decoded,processed,AudioProfile.defaultProfile());
            require(pipeline.getLong("outputFrames")==pipeline.getLong("inputFrames")+pipeline.getInt("tailFrames"),"DSP tail accounting");require(pipeline.getLong("clippedSamples")==0,"Default profile headroom");report.put("offlinePipeline",pipeline);
            android.hardware.usb.UsbManager manager=(android.hardware.usb.UsbManager)context.getSystemService(Context.USB_SERVICE);
            if(manager.getDeviceList().isEmpty()){
                AppFacade facade=new AppFacade(context);try{facade.start();JSONObject status=facade.snapshot();require(!status.optBoolean("running"),"No USB implies no playback");report.put("noUsbStartBlocked",true).put("snapshot",status);}finally{facade.close();}
            }else report.put("noUsbGuardSkipped","Interface conectada; o teste offline não inicia áudio.");
            report.put("ok",true).put("uid",android.os.Process.myUid()).put("scope","App UID, profile persistence, strict WAV, AC3 six channels, full DSP offline and missing-USB guard; no capture/playback.");
            write(new File(dir,"report.json"),report.toString(2));result.putString("report",report.toString());finish(0,result);
        }catch(Throwable e){try{report.put("ok",false).put("error",e.toString());}catch(Exception ignored){}result.putString("report",report.toString());finish(1,result);}
    }
    private static void require(boolean condition,String label){if(!condition)throw new AssertionError(label);}
    private static void write(File file,String text)throws IOException{try(OutputStream out=new FileOutputStream(file)){out.write(text.getBytes(StandardCharsets.UTF_8));}}
}
