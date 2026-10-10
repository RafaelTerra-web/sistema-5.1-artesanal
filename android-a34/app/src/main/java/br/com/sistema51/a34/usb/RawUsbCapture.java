package br.com.sistema51.a34.usb;

/** Native transport building block; not a validated EncodedUsbAudioTransport. */
public final class RawUsbCapture {
    static {System.loadLibrary("sistema51_usb");}
    private RawUsbCapture(){}
    public static native long[] capture(int usbFd,int endpoint,int packetBytes,int seconds,int outputFd,int silentEndpoint,int silentPacketBytes)throws java.io.IOException;
    public static native int reconnect(int usbFd,int interfaceId);
}
