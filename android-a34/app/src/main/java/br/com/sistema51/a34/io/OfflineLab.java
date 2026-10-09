package br.com.sistema51.a34.io;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import br.com.sistema51.a34.ProfileStore;
import br.com.sistema51.a34.dsp.AudioProfile;
import br.com.sistema51.a34.dsp.DspEngine;
import java.io.*;
import java.util.Arrays;

/** Offline file pipeline using the same DSP engine as the USB service. */
public final class OfflineLab {
    public static final class Result { public final JSONObject report;public final File output;Result(JSONObject r,File f){report=r;output=f;} }
    private OfflineLab(){}
    public static Result process(Context context,Uri input,AudioProfile profile)throws Exception{
        File dir=new File(context.getCacheDir(),"offline");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Falha ao criar bancada.");
        File source=new File(dir,"source.audio"),decoded=new File(dir,"decoded.wav");
        File output=null;
        try{
        try(InputStream in=context.getContentResolver().openInputStream(input);OutputStream out=new FileOutputStream(source)){
            if(in==null)throw new IOException("Arquivo não acessível.");byte[] buffer=new byte[32768];long total=0;int count;
            while((count=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Cancelado.");total+=count;if(total>128L*1024*1024)throw new IOException("Limite de teste: 128 MiB.");out.write(buffer,0,count);}
        }
        byte[] signature=new byte[12];try(InputStream in=new FileInputStream(source)){if(in.read(signature)<8)throw new IOException("Arquivo curto.");}
        JSONObject codec=null;File pcm=source;
        if((signature[0]&255)==11&&(signature[1]&255)==119){codec=PlatformAc3Decoder.decode(source,decoded);pcm=decoded;}
        else if(!(signature[0]=='R'&&signature[1]=='I'&&signature[2]=='F'&&signature[3]=='F'))throw new IOException("Escolha WAV PCM16/float32 ou AC-3 elementar a 48 kHz.");
        output=new File(dir,"processed-"+System.currentTimeMillis()+".wav");
            JSONObject report=processWav(pcm,output,profile);if(codec!=null)report.put("decoder",codec);
            report.put("sourceUri",input.toString()).put("outputFile",output.getName());
            return new Result(report,output);
        }catch(Exception e){if(output!=null)output.delete();throw e;}
        finally{source.delete();decoded.delete();}
    }
    public static JSONObject processWav(File source,File output,AudioProfile profile)throws Exception{
        DspEngine engine=new DspEngine(480);engine.setProfile(profile);
        long blocks=0,totalNs=0,maxNs=0,inputFrames=0;int tail=0;for(int c=0;c<6;c++)tail=Math.max(tail,profile.isBypass()?0:profile.getDelaySamples(c));
        float[] input=new float[480*6],processed=new float[480*6];
        try(WavIO.Reader reader=new WavIO.Reader(source);WavIO.Writer writer=new WavIO.Writer(output,6)){
            if(profile.getInputMode()==AudioProfile.InputMode.NATIVE_5_1&&reader.channels!=6)throw new IOException("Arquivo mono/estéreo: selecione o modo Upmix estéreo no perfil.");
            if(profile.getInputMode()==AudioProfile.InputMode.STEREO_UPMIX&&reader.channels==6)throw new IOException("Arquivo 5.1: selecione Preservar 5.1 no perfil.");
            float[] monoStereo=reader.channels==1?new float[480*2]:null;
            int processingChannels=reader.channels==1?2:reader.channels;
            int count;while((count=reader.readFrames(input,480))>0){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Cancelado.");
                if(monoStereo!=null)for(int i=0;i<count;i++){monoStereo[2*i]=input[i];monoStereo[2*i+1]=input[i];}
                long start=System.nanoTime();engine.process(monoStereo==null?input:monoStereo,processingChannels,processed,count);long elapsed=System.nanoTime()-start;totalNs+=elapsed;maxNs=Math.max(maxNs,elapsed);blocks++;
                writer.writeFrames(processed,count);inputFrames+=count;
            }
            Arrays.fill(input,0);if(monoStereo!=null)Arrays.fill(monoStereo,0);for(int remaining=tail;remaining>0;){count=Math.min(480,remaining);engine.process(monoStereo==null?input:monoStereo,processingChannels,processed,count);writer.writeFrames(processed,count);remaining-=count;}
            JSONArray peaks=new JSONArray();for(int c=0;c<6;c++)peaks.put(engine.getChannelPeak(c));
            return new JSONObject().put("ok",true).put("kind","offline_dsp").put("inputFrames",inputFrames).put("outputFrames",writer.frames())
                    .put("tailFrames",tail).put("sampleRate",48000).put("channels",6).put("inputChannels",reader.channels)
                    .put("blocks",blocks).put("processingWallMs",totalNs/1000000.0).put("maxProcessingBlockMs",maxNs/1000000.0)
                    .put("clippedSamples",engine.getClippedSamples()).put("nonFiniteInputSamples",engine.getNonFiniteInputSamples()).put("lastBlockPeaks",peaks)
                    .put("profile",ProfileStore.toJson(profile,"Arquivo"))
                    .put("scope","Arquivo offline. Não mede USB, reprodução simultânea, temperatura ou latência ao vivo.")
                    .put("tailNote","Cauda limitada ao maior delay do perfil; resposta IIR após esse limite não é exportada.");
        }
    }
}
