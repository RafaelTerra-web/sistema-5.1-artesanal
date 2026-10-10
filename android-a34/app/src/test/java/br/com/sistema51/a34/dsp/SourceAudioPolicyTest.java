package br.com.sistema51.a34.dsp;
import org.junit.Test;
import static org.junit.Assert.*;
public final class SourceAudioPolicyTest {
    @Test public void unknownTransportNeverUpmix(){for(int c:new int[]{1,2,6})assertFalse(SourceAudioPolicy.shouldUpmix(SourceAudioPolicy.Codec.UNKNOWN,c));}
    @Test public void onlyKnownNonDolbyMonoStereoUpmix(){for(SourceAudioPolicy.Codec codec:new SourceAudioPolicy.Codec[]{SourceAudioPolicy.Codec.PCM,SourceAudioPolicy.Codec.NON_DOLBY}){assertTrue(SourceAudioPolicy.shouldUpmix(codec,1));assertTrue(SourceAudioPolicy.shouldUpmix(codec,2));assertFalse(SourceAudioPolicy.shouldUpmix(codec,6));}}
    @Test public void ac3AndEac3NeverUpmix(){for(SourceAudioPolicy.Codec codec:new SourceAudioPolicy.Codec[]{SourceAudioPolicy.Codec.AC3,SourceAudioPolicy.Codec.EAC3}){assertFalse(SourceAudioPolicy.shouldUpmix(codec,1));assertFalse(SourceAudioPolicy.shouldUpmix(codec,2));assertFalse(SourceAudioPolicy.shouldUpmix(codec,6));}}
    @Test public void dolbyStereoOverridesUpmixRequest(){assertEquals(AudioProfile.InputMode.NATIVE_5_1,SourceAudioPolicy.profileFor(AudioProfile.builder().inputMode(AudioProfile.InputMode.STEREO_UPMIX).build(),SourceAudioPolicy.Codec.AC3,2).getInputMode());}
    @Test public void nativeStereoDoesNotCreateCenterOrSurround(){float[] out=new float[12];SourceAudioPolicy.expandNative(new float[]{.1f,.2f,-.3f,-.4f},2,out,2);assertArrayEquals(new float[]{.1f,.2f,0,0,0,0,-.3f,-.4f,0,0,0,0},out,0);}
    @Test public void nativeMonoUsesCenter(){float[] out=new float[6];SourceAudioPolicy.expandNative(new float[]{.4f},1,out,1);assertArrayEquals(new float[]{0,0,.4f,0,0,0},out,0);}
}
