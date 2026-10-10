package br.com.sistema51.a34;

import android.content.Context;
import br.com.sistema51.a34.io.SoftwareAc3Decoder;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Offline software-only validation; never claims USB or opens an audio playback device. */
public final class SoftwareAc3DecoderProbe {
    private static final int[] BITRATES={32,40,48,56,64,80,96,112,128,160,192,224,256,320,384,448,512,576,640};
    private SoftwareAc3DecoderProbe() {}

    public static JSONObject run(Context context) throws Exception {
        long begin=System.nanoTime();
        byte[] data;
        try(InputStream in=context.getAssets().open("fixtures/tones-51.ac3");ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))!=-1) { out.write(buffer,0,n);if(out.size()>2*1024*1024)throw new IOException("Fixture fora do limite."); }
            data=out.toByteArray();
        }
        long frames=0,samples=0;float[] peak=new float[6];double[] energy=new double[6];
        boolean crcRejected=false,truncatedRejected=false,resetDeterministic=false;
        float[] reusable=new float[1536*6];
        try(SoftwareAc3Decoder decoder=new SoftwareAc3Decoder();SoftwareAc3Decoder live=new SoftwareAc3Decoder()) {
            byte[] first=null;float[] firstPcm=null;
            for(int p=0;p<data.length;) {
                require(data.length-p>=8&&(data[p]&255)==0x0b&&(data[p+1]&255)==0x77,"Fixture AC-3");
                int code=data[p+4]&63;require(code<=37,"AC-3 size code");int size=BITRATES[code/2]*4;
                require(p+size<=data.length,"Complete fixture frame");
                byte[] packet=Arrays.copyOfRange(data,p,p+size);
                SoftwareAc3Decoder.DecodedFrame frame=decoder.decode(packet);
                require(frame.frames==1536&&frame.channels==6&&frame.sampleRate==48000,"Exact 5.1 frame accounting");
                require(live.decodeInto(packet,reusable)==6,"Reusable decoder channel accounting");
                require(Arrays.equals(frame.interleaved,reusable),"Reusable decoder must preserve every PCM sample");
                for(int i=0;i<frame.interleaved.length;i++) {
                    float value=frame.interleaved[i];require(Float.isFinite(value),"Finite software PCM");int ch=i%6;
                    peak[ch]=Math.max(peak[ch],Math.abs(value));energy[ch]+=value*(double)value;
                }
                if(first==null) { first=packet;firstPcm=frame.interleaved; }
                frames++;samples+=frame.frames;p+=size;
            }
            require(frames>0&&samples==frames*1536,"All frames retained without EOS deficit");
            byte[] bad=first.clone();bad[bad.length/2]^=1;
            try { decoder.decode(bad); } catch(IOException expected) { crcRejected=true; }
            require(crcRejected,"Corrupt AC-3 rejected");
            try { decoder.decode(Arrays.copyOf(first,first.length-1)); } catch(IOException expected) { truncatedRejected=true; }
            require(truncatedRejected,"Truncated AC-3 rejected");
            decoder.reset();resetDeterministic=Arrays.equals(firstPcm,decoder.decode(first).interleaved);
            require(resetDeterministic,"Reset discards previous overlap history");
        }
        JSONArray peaks=new JSONArray(),rms=new JSONArray();
        for(int ch=0;ch<6;ch++) { peaks.put(peak[ch]);rms.put(Math.sqrt(energy[ch]/samples)); }
        return new JSONObject().put("ok",true).put("decoder","FFmpeg 8.0.3 AC-3 float, source built")
                .put("drcScale",0).put("targetLevel",0).put("inputAc3Frames",frames)
                .put("inputSampleFrames",frames*1536).put("outputSampleFrames",samples).put("sampleFrameDifference",0)
                .put("channels",6).put("sampleRate",48000).put("order","FL FR FC LFE SL SR")
                .put("peakByChannel",peaks).put("rmsByChannel",rms).put("crcRejected",crcRejected)
                .put("truncatedRejected",truncatedRejected).put("resetDeterministic",resetDeterministic)
                .put("reusablePcmMatches",true)
                .put("runtimeMs",(System.nanoTime()-begin)/1000000.0)
                .put("scope","Offline software decoder only; no capture, USB, sound output or profile edits.");
    }

    private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
