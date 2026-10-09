package br.com.sistema51.a34;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import br.com.sistema51.a34.dsp.AudioProfile;
import br.com.sistema51.a34.dsp.DspEngine;
import br.com.sistema51.a34.io.WavIO;
import java.io.File;
import java.util.Arrays;

/** A short deterministic test in the application's own UID, without capture or playback. */
public final class SelfTests {
    public static final class Result { public final JSONObject report; public final File output; Result(JSONObject r,File f){report=r;output=f;} }
    private SelfTests(){}
    public static Result run(Context context)throws Exception {
        AudioProfile.Builder builder=AudioProfile.defaultProfile().toBuilder().masterGain(1).muted(false).bypass(false)
                .lfeEqEnabled(false).surroundCrossoverEnabled(false).centerBassCopyEnabled(false).automaticLfeHeadroom(false).lfeHeadroom(1);
        AudioProfile profile=builder.build();DspEngine engine=new DspEngine(480);engine.setProfile(profile);
        int[] positions={2400,7200,12000,16800,21600,26400};int[] first={-1,-1,-1,-1,-1,-1};
        float[] input=new float[480*6],output=new float[480*6];boolean exact=true,finite=true;
        File dir=new File(context.getCacheDir(),"offline");if(!dir.isDirectory()&&!dir.mkdirs())throw new java.io.IOException("Bancada indisponível.");
        File wav=new File(dir,"self-test.wav");int totalFrames=48000,block=480;long begin=System.nanoTime();
        try(WavIO.Writer writer=new WavIO.Writer(wav,6)){
            for(int start=0;start<totalFrames;start+=block){
                Arrays.fill(input,0);for(int c=0;c<6;c++)if(positions[c]>=start&&positions[c]<start+block)input[(positions[c]-start)*6+c]=.25f;
                engine.process(input,6,output,block);
                for(int f=0;f<block;f++)for(int c=0;c<6;c++){
                    float value=output[f*6+c];finite &= Float.isFinite(value);float expected=(start+f==positions[c]+profile.getDelaySamples(c))?.25f:0;
                    exact &= value==expected;if(value!=0&&first[c]<0)first[c]=start+f;
                }
                writer.writeFrames(output,block);
            }
        }
        JSONArray checks=new JSONArray();boolean positionsCorrect=true;
        for(int c=0;c<6;c++){int delay=first[c]-positions[c];positionsCorrect &= delay==profile.getDelaySamples(c);checks.put(new JSONObject().put("channel",c).put("expectedSamples",profile.getDelaySamples(c)).put("observedSamples",delay));}
        AudioProfile restored=ProfileStore.fromJson(ProfileStore.toJson(AudioProfile.defaultProfile(),"Teste"));
        boolean profileValid=restored.isAutomaticLfeHeadroom()&&restored.getDelaySamples(0)==3686;
        AudioProfile.Builder mixBuilder=builder.inputMode(AudioProfile.InputMode.STEREO_UPMIX)
                .upmixCenterGain(.7071f).upmixSurroundGain(.8f).upmixBassGain(.25f)
                .upmixDifference(1).upmixBassCutoffHz(80);
        for(int c=0;c<6;c++)mixBuilder.delaySamples(c,0);
        AudioProfile mix=ProfileStore.fromJson(ProfileStore.toJson(mixBuilder.build(),"Upmix"));
        boolean mixRoundTrip=mix.getUpmixCenterGain()==.7071f&&mix.getUpmixSurroundGain()==.8f
                &&mix.getUpmixBassGain()==.25f&&mix.getUpmixDifference()==1&&mix.getUpmixBassCutoffHz()==80;
        DspEngine mixEngine=new DspEngine(480);mixEngine.setProfile(mix);
        float[] stereo=new float[960],expanded=new float[2880];Arrays.fill(stereo,.3f);
        mixEngine.process(stereo,2,expanded,480);boolean monoSeparated=true;
        for(int f=0;f<480;f++)monoSeparated &= expanded[f*6]==.3f&&expanded[f*6+1]==.3f
                &&Math.abs(expanded[f*6+2]-.3f*.7071f)<1e-7f&&expanded[f*6+4]==0&&expanded[f*6+5]==0;
        JSONObject legacy=ProfileStore.toJson(AudioProfile.defaultProfile(),"Legado");
        for(String key:new String[]{"upmixCenterGain","upmixSurroundGain","upmixBassGain","upmixDifference","upmixBassCutoffHz"})legacy.remove(key);
        AudioProfile migrated=ProfileStore.fromJson(legacy);
        boolean legacyDefaults=migrated.getUpmixCenterGain()==1&&migrated.getUpmixSurroundGain()==.5f
                &&migrated.getUpmixBassGain()==.5f&&migrated.getUpmixDifference()==0&&migrated.getUpmixBassCutoffHz()==120;
        JSONObject report=new JSONObject().put("ok",exact&&finite&&positionsCorrect&&profileValid&&mixRoundTrip&&monoSeparated&&legacyDefaults).put("kind","app_self_test")
                .put("sampleRate",48000).put("channels",6).put("frames",totalFrames).put("sampleExact",exact).put("finite",finite)
                .put("delays",checks).put("profileRoundTrip",profileValid).put("clippedSamples",engine.getClippedSamples())
                .put("upmixRoundTrip",mixRoundTrip).put("upmixMonoSeparation",monoSeparated).put("legacyUpmixDefaults",legacyDefaults)
                .put("elapsedWallMs",(System.nanoTime()-begin)/1000000.0)
                .put("scope","Teste determinístico no aplicativo: seis canais, delays, upmix, migração de perfil, serialização e WAV. Não usa USB, microfone ou reprodução.");
        if(!report.getBoolean("ok"))throw new IllegalStateException("Autoteste falhou: "+report);
        return new Result(report,wav);
    }
}
