package br.com.sistema51.a34.dsp;

import org.junit.Test;
import static org.junit.Assert.*;

/** Signal properties, rather than duplicating the engine's processing loop. */
public final class UpmixTest {
    private static AudioProfile flat() {
        AudioProfile.Builder b = AudioProfile.defaultProfile().toBuilder().masterGain(1)
                .muted(false).lfeEqEnabled(false).surroundCrossoverEnabled(false)
                .centerBassCopyEnabled(false).automaticLfeHeadroom(false).lfeHeadroom(1);
        for (int c=0;c<6;c++) b.delaySamples(c,0);
        return b.build();
    }
    private static float[] render(float[] input, AudioProfile p, int block) {
        DspEngine engine = new DspEngine(block); engine.setProfile(p);
        int channels=p.getRequiredInputChannels(), frames=input.length/channels;
        float[] out=new float[frames*6];
        for(int offset=0;offset<frames;offset+=block) {
            int n=Math.min(block,frames-offset);
            float[] in=new float[n*channels], result=new float[n*6];
            System.arraycopy(input,offset*channels,in,0,in.length);
            engine.process(in,channels,result,n);
            System.arraycopy(result,0,out,offset*6,result.length);
        }
        assertEquals(0,engine.getClippedSamples());
        return out;
    }
    @Test public void ambienceKeepsMonoVoiceInFrontAndCenter() {
        AudioProfile p=flat().toBuilder().inputMode(AudioProfile.InputMode.STEREO_UPMIX)
                .upmixDifference(1).upmixCenterGain(.7071f).build();
        float[] input=new float[4000*2];
        for(int f=0;f<4000;f++) input[2*f]=input[2*f+1]=.3f;
        float[] out=render(input,p,73);
        for(int f=0;f<4000;f++) {
            assertEquals(.3f,out[f*6],0); assertEquals(.3f,out[f*6+1],0);
            assertEquals(.3f*.7071f,out[f*6+2],1e-7f);
            assertEquals(0,out[f*6+4],0); assertEquals(0,out[f*6+5],0);
        }
    }
    @Test public void oppositePhaseIsSeparatedWithoutCreatingCenterOrBass() {
        AudioProfile p=flat().toBuilder().inputMode(AudioProfile.InputMode.STEREO_UPMIX)
                .upmixDifference(1).upmixSurroundGain(1).build();
        float[] out=render(new float[]{1,-1},p,1);
        assertEquals(1,out[4],0); assertEquals(-1,out[5],0);
        assertEquals(0,out[2],0); assertEquals(0,out[3],0);
    }
    @Test public void upmixControlsNeverChangeNativeChannels() {
        float[] input={.2f,.3f,.4f,.1f,-.2f,-.3f};
        AudioProfile nativeProfile=flat();
        assertArrayEquals(render(input,nativeProfile,1),render(input,nativeProfile.toBuilder()
                .upmixDifference(1).upmixCenterGain(0).upmixSurroundGain(0).upmixBassGain(0)
                .upmixBassCutoffHz(40).build(),1),0);
    }
    @Test public void bassCutoffRespondsToFrequencyAndPartitioningIsStable() {
        AudioProfile p=flat().toBuilder().inputMode(AudioProfile.InputMode.STEREO_UPMIX)
                .upmixDifference(.65f).upmixBassGain(.25f).upmixBassCutoffHz(80).build();
        float[] input=new float[48000*2];
        for(int f=0;f<48000;f++) {
            input[2*f]=(float)(.4*Math.sin(2*Math.PI*80*f/48000));
            input[2*f+1]=input[2*f];
        }
        float[] out=render(input,p,480);
        assertArrayEquals(out,render(input,p,53),0);
        double energy=0;for(int f=24000;f<48000;f++) energy+=out[f*6+3]*out[f*6+3];
        assertEquals(.05/Math.sqrt(2),Math.sqrt(energy/24000),1e-6);
    }
    @Test public void invalidControlsCannotEnterRealtimeGraph() {
        for(float v:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-.1f,1.1f}) {
            try {flat().toBuilder().upmixDifference(v).build();fail();}catch(IllegalArgumentException expected){}
            try {flat().toBuilder().upmixBassGain(v).build();fail();}catch(IllegalArgumentException expected){}
        }
        try {flat().toBuilder().upmixBassCutoffHz(0).build();fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void sourceMetadataProtectsQuietNativeScenesEvenWhenUpmixWasSelected(){
        AudioProfile selected=flat().toBuilder().inputMode(AudioProfile.InputMode.STEREO_UPMIX).build();
        float[] quietNative={.2f,.3f,0,0,0,0};
        assertArrayEquals(quietNative,render(quietNative,selected.forSourceChannels(6),1),0);
        assertEquals(AudioProfile.InputMode.STEREO_UPMIX,flat().forSourceChannels(2).getInputMode());
        try{flat().forSourceChannels(8);fail();}catch(IllegalArgumentException expected){}
    }
}
