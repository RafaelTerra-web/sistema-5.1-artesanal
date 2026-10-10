package br.com.sistema51.a34.dsp;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public final class RoomCorrectionDspTest {
    private AudioProfile flat(){
        AudioProfile.Builder b=AudioProfile.defaultProfile().toBuilder().masterGain(.5f).generalEqEnabled(false).lfeEqEnabled(false)
                .surroundCrossoverEnabled(false).centerBassCopyEnabled(false).frontCrossoverEnabled(false).automaticLfeHeadroom(false).lfeHeadroom(1).centerToFrontGain(0);
        for(int c=0;c<6;c++)b.delaySamples(c,0);return b.build();
    }
    private RoomCorrection correction(){
        return new RoomCorrection(true,new RoomCorrection.Filter[][]{{new RoomCorrection.Filter(80,-6,3)},{},{},{},{},{}});
    }
    @Test public void cutsOnlySelectedChannelAndPreservesDiscreteSeparation(){
        DspEngine dsp=new DspEngine(480);dsp.setProfile(flat().toBuilder().roomCorrection(correction()).build());
        float[] input=new float[480*6],output=new float[input.length];double[] power=new double[6];
        for(int chunk=0;chunk<150;chunk++){
            for(int f=0;f<480;f++)for(int c=0;c<6;c++)input[f*6+c]=(float)(.2*Math.sin(2*Math.PI*80*(chunk*480+f)/48000));
            dsp.process(input,output,480);
            if(chunk>=100)for(int i=0;i<output.length;i++)power[i%6]+=output[i]*output[i];
        }
        double ratio=Math.sqrt(power[0]/power[1]);assertEquals(Math.pow(10,-6/20.0),ratio,.003);
        for(int c=1;c<6;c++)assertEquals(power[1],power[c],1e-6);
        assertEquals(0,dsp.getClippedSamples());
        Arrays.fill(input,0);for(int f=0;f<480;f++)input[f*6+2]=.2f;
        dsp.reset();dsp.process(input,output,480);
        for(int f=0;f<480;f++)for(int c=0;c<6;c++)if(c!=2)assertEquals(0,output[f*6+c],0);
    }
    @Test public void roomUpdatePreservesDelayHistoryAndSavedSettings(){
        AudioProfile p=flat().toBuilder().delaySamples(0,3686).build();DspEngine dsp=new DspEngine(480);dsp.setProfile(p);
        float[] input=new float[480*6],out=new float[input.length];Arrays.fill(input,.2f);
        for(int i=0;i<10;i++)dsp.process(input,out,480);
        dsp.setProfile(p.toBuilder().roomCorrection(correction()).build());dsp.process(input,out,480);
        assertTrue(out[0]>.09);assertEquals(3686,dsp.getAppliedProfile().getDelaySamples(0));
        assertEquals(.5,dsp.getProfile().getMasterGain(),0);assertFalse(p.getRoomCorrection().isEnabled());
        assertEquals(correction(),dsp.getProfile().toBuilder().masterGain(.2f).build().getRoomCorrection());
    }
    @Test public void noCorrectionIsExactAndBypassSkipsIt(){
        DspEngine plain=new DspEngine(480),bypass=new DspEngine(480);plain.setProfile(flat());
        bypass.setProfile(flat().toBuilder().bypass(true).roomCorrection(correction()).build());
        float[] input=new float[480*6],a=new float[input.length],b=new float[input.length];
        Random random=new Random(12);for(int i=0;i<input.length;i++)input[i]=random.nextFloat()*.4f-.2f;
        plain.process(input,a,480);bypass.process(input,b,480);assertArrayEquals(a,b,0);
    }
    @Test public void filterInputsAreImmutableAndCannotBoost(){
        RoomCorrection.Filter[][] filters={{new RoomCorrection.Filter(80,-3,2)},{},{},{},{},{}};
        RoomCorrection r=new RoomCorrection(true,filters);filters[0][0]=new RoomCorrection.Filter(100,-5,3);assertEquals(80,r.filter(0,0).frequencyHz,0);
        try{new RoomCorrection.Filter(80,1,3);fail();}catch(IllegalArgumentException expected){}
        try{new RoomCorrection.Filter(Float.NaN,-3,3);fail();}catch(IllegalArgumentException expected){}
    }
}
