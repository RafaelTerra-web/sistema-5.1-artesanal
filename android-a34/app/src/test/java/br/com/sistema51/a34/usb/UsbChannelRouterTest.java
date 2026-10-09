package br.com.sistema51.a34.usb;
import org.junit.Test;
import static org.junit.Assert.*;

public final class UsbChannelRouterTest {
    @Test public void swapsOnlyPhysicalCenterBassPairWithinValidFrames(){
        float[] samples={1,2,3,4,5,6,7,8,9,10,11,12,21,22,23,24,25,26};
        UsbChannelRouter.mapInPlace(samples,2,true);
        assertArrayEquals(new float[]{1,2,4,3,5,6,7,8,10,9,11,12,21,22,23,24,25,26},samples,0);
        UsbChannelRouter.mapInPlace(samples,2,true);
        assertArrayEquals(new float[]{1,2,3,4,5,6,7,8,9,10,11,12,21,22,23,24,25,26},samples,0);
    }
    @Test public void directMapNeverTouchesLogicalSamples(){
        float[] samples={1,2,3,4,5,6};UsbChannelRouter.mapInPlace(samples,1,false);
        assertArrayEquals(new float[]{1,2,3,4,5,6},samples,0);
        try{UsbChannelRouter.mapInPlace(samples,2,true);fail();}catch(IllegalArgumentException expected){}
    }
}
