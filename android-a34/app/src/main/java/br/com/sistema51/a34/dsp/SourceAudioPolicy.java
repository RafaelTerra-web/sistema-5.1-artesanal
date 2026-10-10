package br.com.sistema51.a34.dsp;

/** Upmix only known non-Dolby mono/stereo; preserve Dolby and native multichannel. */
public final class SourceAudioPolicy {
    public enum Codec { PCM, NON_DOLBY, AC3, EAC3, UNKNOWN }
    private SourceAudioPolicy(){}
    public static boolean shouldUpmix(Codec codec,int channels){
        return (channels==1||channels==2)
                &&(codec==Codec.PCM||codec==Codec.NON_DOLBY);
    }
    public static AudioProfile profileFor(AudioProfile profile,Codec codec,int channels){
        if(channels!=1&&channels!=2&&channels!=6)throw new IllegalArgumentException("Supported source channels: 1, 2, 6");
        return profile.toBuilder().inputMode(shouldUpmix(codec,channels)?AudioProfile.InputMode.STEREO_UPMIX:AudioProfile.InputMode.NATIVE_5_1).build();
    }
    /** Native mono occupies FC; native stereo occupies FL/FR. Other positions stay silent. */
    public static void expandNative(float[] source,int channels,float[] destination,int frames){
        if(channels!=1&&channels!=2)throw new IllegalArgumentException("Native expansion needs mono/stereo");
        for(int i=0;i<frames;i++){
            int offset=i*6;for(int c=0;c<6;c++)destination[offset+c]=0;
            if(channels==1)destination[offset+AudioProfile.FC]=source[i];
            else{destination[offset]=source[i*2];destination[offset+1]=source[i*2+1];}
        }
    }
}
