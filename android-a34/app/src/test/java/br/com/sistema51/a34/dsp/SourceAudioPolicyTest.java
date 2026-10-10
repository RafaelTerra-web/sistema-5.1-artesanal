package br.com.sistema51.a34.dsp;
import org.junit.Test;
import static org.junit.Assert.*;
public final class SourceAudioPolicyTest {
    @Test public void unknownTransportNeverUpmix(){for(int c:new int[]{1,2,6})assertFalse(SourceAudioPolicy.shouldUpmix(SourceAudioPolicy.Codec.UNKNOWN,c));}
    @Test public void knownMonoStereoUpmixButSixChannelsRemainDiscrete(){for(SourceAudioPolicy.Codec codec:new SourceAudioPolicy.Codec[]{SourceAudioPolicy.Codec.PCM,SourceAudioPolicy.Codec.NON_DOLBY,SourceAudioPolicy.Codec.AC3}){assertTrue(SourceAudioPolicy.shouldUpmix(codec,1));assertTrue(SourceAudioPolicy.shouldUpmix(codec,2));assertFalse(SourceAudioPolicy.shouldUpmix(codec,6));}}
    @Test public void eac3AndUnknownNeverUpmix(){for(SourceAudioPolicy.Codec codec:new SourceAudioPolicy.Codec[]{SourceAudioPolicy.Codec.EAC3,SourceAudioPolicy.Codec.UNKNOWN}){assertFalse(SourceAudioPolicy.shouldUpmix(codec,1));assertFalse(SourceAudioPolicy.shouldUpmix(codec,2));assertFalse(SourceAudioPolicy.shouldUpmix(codec,6));}}
    @Test public void ac3StereoSelectsUpmixButAc3FiveOneStaysNative(){AudioProfile profile=AudioProfile.builder().inputMode(AudioProfile.InputMode.NATIVE_5_1).build();assertEquals(AudioProfile.InputMode.STEREO_UPMIX,SourceAudioPolicy.profileFor(profile,SourceAudioPolicy.Codec.AC3,2).getInputMode());assertEquals(AudioProfile.InputMode.NATIVE_5_1,SourceAudioPolicy.profileFor(profile,SourceAudioPolicy.Codec.AC3,6).getInputMode());}
    @Test public void nativeStereoDoesNotCreateCenterOrSurround(){float[] out=new float[12];SourceAudioPolicy.expandNative(new float[]{.1f,.2f,-.3f,-.4f},2,out,2);assertArrayEquals(new float[]{.1f,.2f,0,0,0,0,-.3f,-.4f,0,0,0,0},out,0);}
    @Test public void nativeMonoUsesCenter(){float[] out=new float[6];SourceAudioPolicy.expandNative(new float[]{.4f},1,out,1);assertArrayEquals(new float[]{0,0,.4f,0,0,0},out,0);}
}
