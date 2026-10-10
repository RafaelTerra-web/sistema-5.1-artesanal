package br.com.sistema51.a34.dsp;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
public final class DspLiveControlTest {
    private AudioProfile flat(){AudioProfile.Builder b=AudioProfile.builder().masterGain(.5f).lfeEqEnabled(false).surroundCrossoverEnabled(false).automaticLfeHeadroom(false).lfeHeadroom(1).centerToFrontGain(0);for(int c=0;c<6;c++)b.delaySamples(c,0);return b.build();}
    @Test public void gainRampIsContinuousAndMuteIsImmediate(){
        DspEngine e=new DspEngine(480);AudioProfile p=flat();e.setProfile(p);
        float[] in=new float[480*6],out=new float[480*6];Arrays.fill(in,.5f);e.process(in,6,out,480);
        e.setProfile(p.toBuilder().masterGain(.25f).build());e.process(in,6,out,480);
        assertTrue(out[0]>.249f);assertEquals(.125f,out[out.length-1],1e-6f);
        for(int f=1;f<480;f++)assertTrue(Math.abs(out[f*6]-out[(f-1)*6])<.001f);
        e.setProfile(p.toBuilder().muted(true).build());e.process(in,6,out,480);for(float x:out)assertEquals(0,x,0);
    }
    @Test public void eqGestureKeepsCalibratedDelayHistory(){
        DspEngine e=new DspEngine(480);AudioProfile p=flat().toBuilder().delaySamples(0,3686).build();e.setProfile(p);
        float[] in=new float[480*6],out=new float[480*6];Arrays.fill(in,.2f);
        for(int i=0;i<10;i++)e.process(in,6,out,480);
        e.setProfile(p.toBuilder().generalEqGainDb(5,-3).build());e.process(in,6,out,480);
        for(int f=0;f<480;f++)assertTrue("EQ must not clear the 76.8 ms FL delay",out[f*6]>.03f);
        e.reset();e.process(in,6,out,480);assertEquals(0,out[0],0);
    }
    @Test public void eqHeadroomDoesNotJumpWhenBoostIsEdited(){
        DspEngine e=new DspEngine(480);AudioProfile p=flat().toBuilder().generalEqEnabled(true).build();e.setProfile(p);
        float[] in=new float[480*6],out=new float[480*6];Arrays.fill(in,.2f);e.process(in,6,out,480);
        e.setProfile(p.toBuilder().generalEqGainDb(0,6).build());e.process(in,6,out,480);
        assertEquals("Compensation must ramp with the filter rather than halve the first sample",.1f,out[0],.002f);
        for(int f=1;f<480;f++)assertTrue("No abrupt headroom step",Math.abs(out[f*6]-out[(f-1)*6])<.003f);
    }
}
