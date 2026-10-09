package br.com.sistema51.a34.dsp;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DelayProfileUpdateTest {
    @Test public void delayOnlyUpdatePreservesEveryOtherProfileField() {
        AudioProfile.Builder builder=AudioProfile.defaultProfile().toBuilder().masterGain(.23f).muted(true).bypass(true)
                .inputMode(AudioProfile.InputMode.STEREO_UPMIX).upmixCenterGain(.8f).upmixSurroundGain(.3f)
                .upmixBassGain(.2f).upmixDifference(.7f).upmixBassCutoffHz(110).frontCrossoverEnabled(true)
                .frontCutoffHz(105).frontBassSend(.4f).lfeSubsonicEnabled(true).lfeSubsonicHz(30)
                .swapCenterLfe(true).lfeEqEnabled(false).lfeHeadroom(.15f).automaticLfeHeadroom(false)
                .surroundCrossoverEnabled(false).surroundCutoffHz(100).surroundBassSend(.6f)
                .centerBassCopyEnabled(true).centerBassCutoffHz(80).centerBassSend(.7f);
        for(int c=0;c<6;c++)builder.channelTrim(c,.2f+c*.1f).delaySamples(c,c*100);
        for(int band=0;band<9;band++)builder.lfeEqFrequencyHz(band,15+band*15).lfeEqGainDb(band,band-8).lfeEqQ(band,1+band*.3f);
        AudioProfile before=builder.build();
        int[] delays=DelayProfileUpdate.parseSamples("3686, 3686,278,278,3408,3408");
        AudioProfile after=DelayProfileUpdate.apply(before,delays);
        for(int c=0;c<6;c++){assertEquals(delays[c],after.getDelaySamples(c));assertEquals(c*100,before.getDelaySamples(c));assertEquals(before.getChannelTrim(c),after.getChannelTrim(c),0);}
        delays[0]=0;assertEquals(3686,after.getDelaySamples(0));
        assertEquals(before.getMasterGain(),after.getMasterGain(),0);assertEquals(before.isMuted(),after.isMuted());assertEquals(before.isBypass(),after.isBypass());assertEquals(before.getInputMode(),after.getInputMode());
        assertEquals(before.getUpmixCenterGain(),after.getUpmixCenterGain(),0);assertEquals(before.getUpmixSurroundGain(),after.getUpmixSurroundGain(),0);assertEquals(before.getUpmixBassGain(),after.getUpmixBassGain(),0);assertEquals(before.getUpmixDifference(),after.getUpmixDifference(),0);assertEquals(before.getUpmixBassCutoffHz(),after.getUpmixBassCutoffHz(),0);
        assertEquals(before.isFrontCrossoverEnabled(),after.isFrontCrossoverEnabled());assertEquals(before.getFrontCutoffHz(),after.getFrontCutoffHz(),0);assertEquals(before.getFrontBassSend(),after.getFrontBassSend(),0);assertEquals(before.isLfeSubsonicEnabled(),after.isLfeSubsonicEnabled());assertEquals(before.getLfeSubsonicHz(),after.getLfeSubsonicHz(),0);assertEquals(before.isSwapCenterLfe(),after.isSwapCenterLfe());
        assertEquals(before.isLfeEqEnabled(),after.isLfeEqEnabled());assertEquals(before.getLfeHeadroom(),after.getLfeHeadroom(),0);assertEquals(before.isAutomaticLfeHeadroom(),after.isAutomaticLfeHeadroom());assertEquals(before.getEffectiveLfeHeadroom(),after.getEffectiveLfeHeadroom(),0);
        assertEquals(before.isSurroundCrossoverEnabled(),after.isSurroundCrossoverEnabled());assertEquals(before.getSurroundCutoffHz(),after.getSurroundCutoffHz(),0);assertEquals(before.getSurroundBassSend(),after.getSurroundBassSend(),0);assertEquals(before.isCenterBassCopyEnabled(),after.isCenterBassCopyEnabled());assertEquals(before.getCenterBassCutoffHz(),after.getCenterBassCutoffHz(),0);assertEquals(before.getCenterBassSend(),after.getCenterBassSend(),0);
        for(int band=0;band<9;band++){assertEquals(before.getLfeEqFrequencyHz(band),after.getLfeEqFrequencyHz(band),0);assertEquals(before.getLfeEqGainDb(band),after.getLfeEqGainDb(band),0);assertEquals(before.getLfeEqQ(band),after.getLfeEqQ(band),0);}
    }
    @Test public void invalidDelayListsAreRejectedBeforeChangingTheProfile() {
        for(String csv:new String[]{null,"","1,2","1,2,3,4,5,6,7","1,2,-1,4,5,6","1,2,3.5,4,5,6","1,2,12001,4,5,6","1,2,NaN,4,5,6"}) {
            try{DelayProfileUpdate.parseSamples(csv);fail("Accepted invalid delays");}catch(IllegalArgumentException expected){}
        }
        try{DelayProfileUpdate.apply(AudioProfile.defaultProfile(),new int[]{0,0,0,0,0,-1});fail();}catch(IllegalArgumentException expected){}
    }
}
