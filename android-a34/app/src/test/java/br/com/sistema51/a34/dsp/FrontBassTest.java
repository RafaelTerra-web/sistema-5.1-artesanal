package br.com.sistema51.a34.dsp;
import org.junit.Test;
import static org.junit.Assert.*;

public final class FrontBassTest {
    private static AudioProfile flat(){
        AudioProfile.Builder b=AudioProfile.defaultProfile().toBuilder().masterGain(1).lfeEqEnabled(false)
            .surroundCrossoverEnabled(false).centerBassCopyEnabled(false).automaticLfeHeadroom(false).lfeHeadroom(1);
        for(int c=0;c<6;c++)b.delaySamples(c,0);
        return b.build();
    }
    private static float[] tone(int channel,double hz){
        float[] in=new float[48000*6];
        for(int f=0;f<48000;f++)in[f*6+channel]=(float)(.3*Math.sin(2*Math.PI*hz*f/48000));
        return in;
    }
    private static float[] render(float[] in,AudioProfile p){
        DspEngine e=new DspEngine(480);e.setProfile(p);float[] out=new float[in.length];
        float[] chunk=new float[2880],result=new float[2880];
        for(int f=0;f<in.length/6;f+=480){
            int n=Math.min(480,in.length/6-f);System.arraycopy(in,f*6,chunk,0,n*6);
            e.process(chunk,6,result,n);System.arraycopy(result,0,out,f*6,n*6);
        }
        assertEquals(0,e.getClippedSamples());return out;
    }
    private static double gain(float[] out,int channel){
        double energy=0;for(int f=24000;f<48000;f++)energy+=out[f*6+channel]*out[f*6+channel];
        return Math.sqrt(energy/24000)/(.3/Math.sqrt(2));
    }
    @Test public void lowFrontAndCenterBassIsRedirectedWithoutChannelCrosstalk(){
        AudioProfile p=flat().toBuilder().frontCrossoverEnabled(true).frontCutoffHz(90).build();
        for(int c=0;c<3;c++){
            float[] out=render(tone(c,40),p);
            assertTrue(gain(out,c)<.04);assertTrue(gain(out,3)>.96);
            for(int other=0;other<6;other++)if(other!=c&&other!=3)assertEquals(0,gain(out,other),0);
        }
        float[] high=render(tone(0,1000),p);assertTrue(gain(high,0)>.999);assertTrue(gain(high,3)<.0001);
    }
    @Test public void centralCopyIsNotAddedTwiceAndHeadroomCountsAllSources(){
        AudioProfile p=flat().toBuilder().frontCrossoverEnabled(true).build();float[] in=tone(2,40);
        assertArrayEquals(render(in,p),render(in,p.toBuilder().centerBassCopyEnabled(true).build()),0);
        AudioProfile bounded=p.toBuilder().automaticLfeHeadroom(true).surroundCrossoverEnabled(true).build();
        assertEquals(1f/6,bounded.getEffectiveLfeHeadroom(),0);
        assertEquals(bounded.getEffectiveLfeHeadroom(),bounded.toBuilder().centerBassCopyEnabled(true).build().getEffectiveLfeHeadroom(),0);
    }
    @Test public void redirectedBassUsesLfeDelayAndSubsonicReducesInfrabass(){
        AudioProfile p=flat().toBuilder().frontCrossoverEnabled(true).delaySamples(0,200).delaySamples(3,10).build();
        float[] input=new float[480*6];input[0]=.25f;float[] out=render(input,p);
        assertTrue(out[10*6+3]!=0);assertEquals(0,out[10*6],0);assertTrue(out[200*6]!=0);
        float[] sub=render(tone(3,10),flat().toBuilder().lfeSubsonicEnabled(true).lfeSubsonicHz(20).build());
        assertEquals(.25/Math.sqrt(1.0625),gain(sub,3),1e-5);
    }
    @Test public void extremeInvalidCutsAreRejected(){
        for(float value:new float[]{Float.NaN,Float.POSITIVE_INFINITY,0,200}){
            try{flat().toBuilder().frontCutoffHz(value).build();fail();}catch(IllegalArgumentException expected){}
            try{flat().toBuilder().lfeSubsonicHz(value).build();fail();}catch(IllegalArgumentException expected){}
        }
    }
}
