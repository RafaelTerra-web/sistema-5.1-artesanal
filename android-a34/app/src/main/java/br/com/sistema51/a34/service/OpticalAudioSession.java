package br.com.sistema51.a34.service;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.os.Process;
import android.os.SystemClock;
import br.com.sistema51.a34.dsp.AudioProfile;
import br.com.sistema51.a34.dsp.DspEngine;
import br.com.sistema51.a34.dsp.ClockDriftResampler;
import br.com.sistema51.a34.dsp.SourceAudioPolicy;
import br.com.sistema51.a34.io.Iec61937Ac3Stream;
import br.com.sistema51.a34.io.SoftwareAc3Decoder;
import br.com.sistema51.a34.usb.Cm6206DuplexTransport;
import br.com.sistema51.a34.usb.UsbChannelRouter;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the optical pipeline on one audio worker; controls publish immutable profiles. */
final class OpticalAudioSession implements Runnable {
    interface Listener { void status(JSONObject snapshot); void ended(String error); }
    private final Context context;
    private final UsbDevice device;
    private final UsbDeviceConnection connection;
    private final Listener listener;
    private final AtomicBoolean stopped=new AtomicBoolean();
    private final Object gateLock=new Object();
    private volatile AudioProfile profile;
    private volatile Cm6206DuplexTransport transport;
    private volatile boolean signalReady;
    private volatile String gateFailure="";
    OpticalAudioSession(Context context,UsbDevice device,UsbDeviceConnection connection,AudioProfile profile,Listener listener){
        this.context=context;this.device=device;this.connection=connection;this.profile=profile;this.listener=listener;
    }
    void updateProfile(AudioProfile profile){
        synchronized(gateLock){
        this.profile=profile;
        Cm6206DuplexTransport active=transport;
        if(active!=null&&(profile.isMuted()||!signalReady))try{active.setMuted(true);}catch(IOException failure){gateFailure=failure.getMessage();}
        }
    }
    void stop(){synchronized(gateLock){stopped.set(true);signalReady=false;Cm6206DuplexTransport active=transport;if(active!=null)try{active.setMuted(true);}catch(IOException ignored){}}}
    @Override public void run(){
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        Cm6206DuplexTransport duplex=new Cm6206DuplexTransport(context);
        SoftwareAc3Decoder decoder=null;
        DspEngine engine=new DspEngine(1536);
        Iec61937Ac3Stream parser=new Iec61937Ac3Stream();
        ClockDriftResampler resampler=new ClockDriftResampler();
        byte[] carrier=new byte[8192],pcm16=new byte[1540*12];
        float[] output=new float[1536*6],monoStereo=new float[1536*2],nativeSix=new float[1536*6],decodedPcm=new float[1536*6];
        float[] adapted=new float[1540*6];
        double[] squares=new double[6],decodedPeaks=new double[6];
        long[] clockStats=new long[27];
        AudioProfile applied=null;
        int sourceChannels=0,consecutive=0;
        long decoded=0,submitted=0,lastValid=SystemClock.elapsedRealtime(),lastSnapshot=0,processingNanos=0;
        long saturated=0,resamplingClipped=0;
        double processingPeakPercent=0;
        long clippedTotal=0,engineClippedPrevious=0;
        String error="";
        JSONObject lastPublished=null;
        try{
            if(stopped.get()){connection.close();return;}
            duplex.open(device,connection);transport=duplex;duplex.setMuted(true);
            while(!stopped.get()){
                if(!gateFailure.isEmpty())throw new IOException("Controle de mute USB falhou: "+gateFailure);
                int count=duplex.read(carrier,0,carrier.length,100);
                if(count<0)throw new IOException("Transporte óptico encerrado.");
                if(count>0)parser.append(carrier,count);
                Iec61937Ac3Stream.Frame burst;
                while(!stopped.get()&&(burst=parser.poll())!=null){
                    long computeStarted=System.nanoTime();
                    long now=SystemClock.elapsedRealtime();
                    if(burst.discontinuity||sourceChannels!=burst.channels||now-lastValid>150){
                        signalReady=false;duplex.setMuted(true);duplex.clearOutput();consecutive=0;
                        if(decoder!=null){decoder.close();decoder=null;}engine.reset();resampler.reset();applied=null;
                    }
                    if(decoder==null)decoder=new SoftwareAc3Decoder();
                    int decodedChannels=decoder.decodeInto(burst.ac3,decodedPcm);
                    if(decodedChannels!=burst.channels)
                        throw new IOException("Decoder alterou contagem, canais ou taxa do quadro AC-3.");
                    sourceChannels=decodedChannels;lastValid=now;decoded+=1536;
                    java.util.Arrays.fill(decodedPeaks,0);
                    for(int f=0;f<1536;f++)for(int c=0;c<sourceChannels;c++)decodedPeaks[c]=Math.max(decodedPeaks[c],Math.abs(decodedPcm[f*sourceChannels+c]));
                    AudioProfile selected=profile;
                    if(selected!=applied){if(applied!=null&&applied.isMuted()!=selected.isMuted())resampler.reset();engine.setProfile(SourceAudioPolicy.profileFor(selected,SourceAudioPolicy.Codec.AC3,sourceChannels));applied=selected;}
                    int inputChannels=sourceChannels;
                    float[] input=decodedPcm;
                    if(sourceChannels<6&&!SourceAudioPolicy.shouldUpmix(SourceAudioPolicy.Codec.AC3,sourceChannels)){
                        SourceAudioPolicy.expandNative(decodedPcm,sourceChannels,nativeSix,1536);
                        input=nativeSix;inputChannels=6;
                    }else if(sourceChannels==1){
                        for(int f=0;f<1536;f++)monoStereo[f*2]=monoStereo[f*2+1]=input[f];
                        input=monoStereo;inputChannels=2;
                    }
                    engine.process(input,inputChannels,output,1536);
                    long engineClipped=engine.getClippedSamples();clippedTotal+=engineClipped>=engineClippedPrevious?engineClipped-engineClippedPrevious:engineClipped;engineClippedPrevious=engineClipped;
                    java.util.Arrays.fill(squares,0);
                    for(int f=0;f<1536;f++)for(int c=0;c<6;c++){double sample=output[f*6+c];squares[c]+=sample*sample;}
                    for(float value:output)if(!Float.isFinite(value))throw new IOException("DSP produziu amostra não finita.");
                    for(float value:output)if(Math.abs(value)>=.9999f)saturated++;
                    duplex.readStats(clockStats);
                    int queue=profile.isMuted()||clockStats[16]!=0?1536:(int)(clockStats[7]/12);
                    int outputFrames=resampler.process(output,1536,adapted,queue);
                    UsbChannelRouter.mapInPlace(adapted,outputFrames,engine.getAppliedProfile().isSwapCenterLfe());
                    int pcmBytes=outputFrames*12;
                    for(int i=0;i<outputFrames*6;i++){
                        if(!Float.isFinite(adapted[i]))throw new IOException("Resampling produziu amostra não finita.");
                        if(Math.abs(adapted[i])>1)resamplingClipped++;
                        int value=Math.round(Math.max(-1f,Math.min(1f,adapted[i]))*32767f);
                        pcm16[i*2]=(byte)value;pcm16[i*2+1]=(byte)(value>>>8);
                    }
                    if(++consecutive>=2){synchronized(gateLock){signalReady=!stopped.get();duplex.setMuted(!signalReady||profile.isMuted());}}
                    long computeNanos=System.nanoTime()-computeStarted;
                    processingNanos+=computeNanos;
                    processingPeakPercent=Math.max(processingPeakPercent,computeNanos/320000.0);
                    int accepted=0;long deadline=SystemClock.elapsedRealtime()+500;
                    while(accepted<pcmBytes&&!stopped.get()){
                        int n=duplex.write(pcm16,accepted,pcmBytes-accepted,50);
                        if(n<0||n%12!=0)throw new IOException("Saída USB retornou quadro incompleto.");
                        accepted+=n;
                        if(SystemClock.elapsedRealtime()>deadline)throw new IOException("Fila PCM USB cheia por 500 ms; sessão interrompida.");
                    }
                    submitted+=accepted/12;
                }
                long now=SystemClock.elapsedRealtime();
                if(now-lastValid>150&&sourceChannels!=0){
                    sourceChannels=0;signalReady=false;duplex.setMuted(true);duplex.clearOutput();consecutive=0;applied=null;engine.reset();resampler.reset();
                    if(decoder!=null){decoder.close();decoder=null;}
                }
                if(now-lastSnapshot>=500){
                    JSONObject stats=duplex.statsJson();JSONArray peaks=new JSONArray(),rms=new JSONArray(),decodedLevels=new JSONArray();
                    double queueMs=stats.optInt("outputQueuedFrames")/48.0;
                    int minimumDelay=12000,maximumDelay=0;
                    for(int c=0;c<6;c++){minimumDelay=Math.min(minimumDelay,profile.getDelaySamples(c));maximumDelay=Math.max(maximumDelay,profile.getDelaySamples(c));}
                    for(int c=0;c<6;c++){float peak=sourceChannels==0?0:engine.getChannelPeak(c);peaks.put(peak);rms.put(sourceChannels==0?0:Math.sqrt(squares[c]/1536));decodedLevels.put(sourceChannels==0?0:decodedPeaks[c]);}
                    lastPublished=new JSONObject().put("sourceCodec",sourceChannels==0?"waiting":"AC3").put("sourceChannels",sourceChannels)
                        .put("upmixActive",SourceAudioPolicy.shouldUpmix(SourceAudioPolicy.Codec.AC3,sourceChannels)).put("runtimeMuted",profile.isMuted()||!signalReady)
                        .put("signalReady",signalReady).put("capturedFrames",decoded).put("reproducedFrames",submitted)
                        .put("iecValidFrames",parser.validFrames).put("sourceDiscontinuities",parser.discontinuities)
                        .put("sourceCrcErrors",parser.crcErrors)
                        .put("underruns",stats.optLong("underrunEvents"))
                        .put("clippingSamples",clippedTotal+resamplingClipped).put("resamplingClippedSamples",resamplingClipped).put("saturatedSamples",saturated)
                        .put("processingPercent",decoded==0?0:processingNanos*48000.0/decoded/1e9*100)
                        .put("processingPeakPercent",processingPeakPercent)
                        .put("clockCorrectionPpm",resampler.getCorrectionPpm()).put("resamplingRatio",resampler.getRatio())
                        .put("resamplingQuality","cubic-4-point").put("decoderFrameMs",32).put("softwareQueueMs",queueMs)
                        .put("usbSubmittedMs",stats.optInt("submittedOutputLatencyMs",16))
                        .put("usbInputBatchMs",16).put("usbInputSubmittedMs",64)
                        .put("channelDelayMinMs",minimumDelay/48.0).put("channelDelayMaxMs",maximumDelay/48.0)
                        .put("latencyEstimateMinMs",32+queueMs+16+minimumDelay/48.0)
                        .put("latencyEstimateMaxMs",32+queueMs+16+maximumDelay/48.0)
                        .put("latencyMeasured",false)
                        .put("peaks",peaks).put("meters",rms).put("decodedPeaks",decodedLevels).put("nativeUsb",stats);
                    listener.status(lastPublished);
                    lastSnapshot=now;
                }
            }
        }catch(Exception|LinkageError failure){if(!stopped.get())error=failure.getClass().getSimpleName()+": "+failure.getMessage();}
        finally{
            signalReady=false;transport=null;
            try{duplex.setMuted(true);}catch(Exception ignored){}
            try{duplex.close();}catch(Exception failure){if(error.isEmpty())error="Falha ao restaurar USB: "+failure.getMessage();}
            try{
                JSONObject ended=lastPublished==null?new JSONObject():new JSONObject(lastPublished.toString());
                ended.put("nativeUsb",duplex.statsJson()).put("runtimeMuted",true).put("signalReady",false);
                listener.status(ended);
            }catch(Exception ignored){}
            if(decoder!=null)decoder.close();
            listener.ended(error);
        }
    }
}
