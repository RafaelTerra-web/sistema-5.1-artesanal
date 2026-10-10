package br.com.sistema51.controller;

import android.content.Context;
import android.media.*;
import android.media.audiofx.*;
import android.os.Build;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground-only native capture; permission is requested by the activity. */
final class RoomMicrophone implements AutoCloseable {
    static final int RATE=48000,MAX_FRAMES=RATE*20;
    private final AudioRecord recorder;
    private final List<AudioEffect> effects=new ArrayList<>();
    private final CountDownLatch ready=new CountDownLatch(1),finished=new CountDownLatch(1);
    private volatile boolean cancelled,raw;
    private volatile int frames,target=MAX_FRAMES;
    private volatile byte[] result;
    private volatile Exception error;
    private final AtomicBoolean started=new AtomicBoolean(),released=new AtomicBoolean();
    @android.annotation.SuppressLint("MissingPermission")
    RoomMicrophone(Context context)throws IOException{
        AudioManager audio=context.getSystemService(AudioManager.class);
        boolean supported="true".equals(audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        int source=supported?MediaRecorder.AudioSource.UNPROCESSED:MediaRecorder.AudioSource.VOICE_RECOGNITION;
        int minimum=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(minimum<=0)throw new IOException("O microfone não oferece gravação mono a 48 kHz.");
        recorder=new AudioRecord.Builder().setAudioSource(source)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(Math.max(8192,minimum*4)).build();
        if(recorder.getState()!=AudioRecord.STATE_INITIALIZED){recorder.release();throw new IOException("Não foi possível abrir o microfone.");}
        AudioDeviceInfo builtin=null;
        for(AudioDeviceInfo device:audio.getDevices(AudioManager.GET_DEVICES_INPUTS))
            if(device.getType()==AudioDeviceInfo.TYPE_BUILTIN_MIC){builtin=device;break;}
        if(builtin==null||!recorder.setPreferredDevice(builtin)){recorder.release();throw new IOException("Não foi possível selecionar o microfone interno.");}
        raw=supported;
        try{if(!supported){
            if(AutomaticGainControl.isAvailable())disable(AutomaticGainControl.create(recorder.getAudioSessionId()));
            if(NoiseSuppressor.isAvailable())disable(NoiseSuppressor.create(recorder.getAudioSessionId()));
            if(AcousticEchoCanceler.isAvailable())disable(AcousticEchoCanceler.create(recorder.getAudioSessionId()));
        }}catch(RuntimeException failure){raw=false;}
    }
    private void disable(AudioEffect effect){
        if(effect==null){raw=false;return;}
        effects.add(effect);
        if(effect.setEnabled(false)!=AudioEffect.SUCCESS||effect.getEnabled())raw=false;
    }
    boolean rawCapture(){return raw;}
    void start(){
        if(!started.compareAndSet(false,true)||cancelled){releaseResources();throw new CancellationException("Medição cancelada.");}
        new Thread(()->{
            try{
                if(cancelled)throw new CancellationException("Medição cancelada.");
                recorder.startRecording();
                if(recorder.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IOException("O microfone não começou a gravar.");
                short[] block=new short[960];
                ByteArrayOutputStream bytes=new ByteArrayOutputStream(MAX_FRAMES*2);
                boolean routeChecked=false;
                while(!cancelled&&frames<Math.min(target,MAX_FRAMES)){
                    int count=recorder.read(block,0,Math.min(block.length,Math.min(target,MAX_FRAMES)-frames),AudioRecord.READ_BLOCKING);
                    if(count<=0)throw new IOException("A gravação foi interrompida ("+count+").");
                    if(!routeChecked){
                        AudioDeviceInfo routed=recorder.getRoutedDevice();
                        if(routed==null||routed.getType()!=AudioDeviceInfo.TYPE_BUILTIN_MIC)throw new IOException("O áudio não veio do microfone interno.");
                        if(Build.VERSION.SDK_INT>=29){
                            AudioRecordingConfiguration config=recorder.getActiveRecordingConfiguration();
                            if(config==null||config.isClientSilenced()||config.getAudioSource()!=MediaRecorder.AudioSource.UNPROCESSED)raw=false;
                        }else raw=false; // Actual capture configuration cannot be confirmed on API 28.
                        routeChecked=true;ready.countDown();
                    }
                    for(int i=0;i<count;i++){bytes.write(block[i]&255);bytes.write((block[i]>>>8)&255);}
                    frames+=count;
                }
                if(cancelled)throw new CancellationException("Medição cancelada.");
                AudioDeviceInfo routed=recorder.getRoutedDevice();
                if(routed==null||routed.getType()!=AudioDeviceInfo.TYPE_BUILTIN_MIC)throw new IOException("O microfone mudou durante a gravação.");
                if(Build.VERSION.SDK_INT>=29){
                    AudioRecordingConfiguration config=recorder.getActiveRecordingConfiguration();
                    if(config==null||config.isClientSilenced()||config.getAudioSource()!=MediaRecorder.AudioSource.UNPROCESSED)raw=false;
                }
                result=bytes.toByteArray();
            }catch(Exception failure){error=failure;}
            finally{
                try{recorder.stop();}catch(RuntimeException ignored){}
                releaseResources();ready.countDown();finished.countDown();
            }
        },"A55-room-microphone").start();
    }
    void awaitReady()throws Exception{
        if(!ready.await(3,TimeUnit.SECONDS))throw new IOException("O microfone não respondeu.");
        if(error!=null)throw error;
        if(cancelled)throw new CancellationException();
    }
    void finishAfter(int milliseconds){target=Math.min(MAX_FRAMES,frames+(int)Math.ceil(milliseconds*48.0));}
    byte[] awaitResult()throws Exception{
        if(!finished.await(23,TimeUnit.SECONDS)){close();throw new IOException("A gravação excedeu o tempo previsto.");}
        if(error!=null)throw error;
        return result;
    }
    private void releaseResources(){if(released.compareAndSet(false,true)){for(AudioEffect effect:effects)effect.release();recorder.release();}}
    @Override public void close(){cancelled=true;try{recorder.stop();}catch(RuntimeException ignored){}if(!started.get())releaseResources();}
}
