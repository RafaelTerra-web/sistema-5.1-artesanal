package br.com.sistema51.a34.usb;

/** Physical pair mapping after the logical DSP; file exports/meters stay FL FR FC LFE SL SR. */
public final class UsbChannelRouter {
    private UsbChannelRouter(){}
    public static void mapInPlace(float[] output,int frames,boolean swapCenterLfe) {
        if(output==null||frames<0||frames>output.length/6)throw new IllegalArgumentException("Buffer USB inválido.");
        if(!swapCenterLfe)return;
        for(int f=0;f<frames;f++){
            int i=f*6;float center=output[i+2];output[i+2]=output[i+3];output[i+3]=center;
        }
    }
}
