package br.com.sistema51.a34.usb;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.SystemClock;
import org.json.JSONObject;

/** Short, bounded USB output test using zero samples only; never chooses another device. */
public final class UsbSilentPlaybackProbe {
    private UsbSilentPlaybackProbe(){}
    public static JSONObject run(Context context)throws Exception{
        AudioManager audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);AudioDeviceInfo selected=null;
        for(AudioDeviceInfo device:audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)){
            if(device.getType()!=AudioDeviceInfo.TYPE_USB_DEVICE&&device.getType()!=AudioDeviceInfo.TYPE_USB_HEADSET)continue;
            boolean six=false;for(int mask:device.getChannelIndexMasks())if(mask==0x3f)six=true;
            if(six){if(selected!=null)throw new IllegalStateException("Mais de uma saída USB compatível; escolha sem ambiguidade.");selected=device;}
        }
        if(selected==null)throw new IllegalStateException("Nenhuma saída USB anuncia seis posições de canais.");
        AudioTrack track=null;long started=SystemClock.elapsedRealtime(),framesWritten=0;int writes=0,zeroWrites=0;boolean routeVerified=false;
        JSONObject result=new JSONObject().put("kind","usb_silent_playback").put("onlyZeroSamples",true)
                .put("requestedChannels",6).put("sampleRate",48000).put("requestedChannelIndexMask",63)
                .put("selectedId",selected.getId()).put("selectedProduct",selected.getProductName()).put("selectedAddress",selected.getAddress());
        try{
            int minimum=AudioTrack.getMinBufferSize(48000,AudioFormat.CHANNEL_OUT_5POINT1,AudioFormat.ENCODING_PCM_16BIT);
            if(minimum<=0)throw new IllegalStateException("Buffer PCM16 indisponível.");
            track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelIndexMask(0x3f).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(minimum*2,480*6*2*4)).build();
            if(track.getState()!=AudioTrack.STATE_INITIALIZED||track.getChannelCount()!=6)throw new IllegalStateException("AudioTrack recusou seis canais.");
            if(!track.setPreferredDevice(selected))throw new IllegalStateException("Preferência USB recusada.");
            short[] zeros=new short[480*6];track.play();long deadline=SystemClock.elapsedRealtime()+3000;
            while(SystemClock.elapsedRealtime()<deadline){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException("Cancelado.");
                AudioDeviceInfo actual=track.getRoutedDevice();
                if(actual!=null){if(actual.getId()!=selected.getId()||actual.getType()!=selected.getType())throw new IllegalStateException("Saída mudou para outro dispositivo.");routeVerified=true;}
                int count=track.write(zeros,0,zeros.length,AudioTrack.WRITE_NON_BLOCKING);
                if(count<0||count%6!=0)throw new IllegalStateException("Escrita PCM inválida: "+count);
                if(count>0){framesWritten+=count/6;writes++;}else{zeroWrites++;SystemClock.sleep(2);}
            }
            AudioDeviceInfo actual=track.getRoutedDevice();if(actual==null||actual.getId()!=selected.getId())throw new IllegalStateException("Rota USB não confirmada.");
            long playbackFrames=Integer.toUnsignedLong(track.getPlaybackHeadPosition());
            result.put("ok",routeVerified&&framesWritten>0&&playbackFrames>0).put("routeVerified",routeVerified)
                    .put("actualId",actual.getId()).put("actualChannels",track.getChannelCount()).put("actualChannelIndexMask",track.getFormat().getChannelIndexMask())
                    .put("framesWritten",framesWritten).put("playbackHeadFrames",playbackFrames).put("writeCalls",writes).put("zeroWriteCalls",zeroWrites)
                    .put("underruns",track.getUnderrunCount()).put("elapsedMs",SystemClock.elapsedRealtime()-started)
                    .put("scope","Real AudioTrack, six channel slots, verified USB route and API frame progress, zero samples only. Does not verify analog connector map, acoustic output, capture or AC-3.");
            return result;
        }finally{if(track!=null){try{track.pause();track.flush();track.stop();}catch(RuntimeException ignored){}track.release();}}
    }
}
