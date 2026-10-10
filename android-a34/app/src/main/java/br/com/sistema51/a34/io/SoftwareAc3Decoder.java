package br.com.sistema51.a34.io;

import java.io.IOException;

/** Source-built FFmpeg float AC-3 decoder. Accepts one validated elementary frame, never IEC carrier. */
public final class SoftwareAc3Decoder implements AutoCloseable {
    static { System.loadLibrary("sistema51_ac3"); }
    private long handle;
    private final int[] liveMetadata = new int[3];

    public static final class DecodedFrame {
        /** Mono FC, stereo FL/FR, or canonical DSP FL/FR/FC/LFE/SL/SR. */
        public final float[] interleaved;
        public final int channels, sampleRate, frames;
        private DecodedFrame(float[] samples, int[] metadata) {
            interleaved = samples;
            channels = metadata[0]; sampleRate = metadata[1]; frames = metadata[2];
        }
    }

    public SoftwareAc3Decoder() throws IOException {
        handle = nativeCreate();
        if (handle == 0) throw new IOException("Não foi possível criar o decoder AC-3.");
    }

    public synchronized DecodedFrame decode(byte[] completeAc3Frame) throws IOException {
        if (handle == 0) throw new IOException("Decoder AC-3 fechado.");
        if (completeAc3Frame == null || completeAc3Frame.length < 128 || completeAc3Frame.length > 2560)
            throw new IOException("Quadro AC-3 fora do limite.");
        int[] metadata = new int[3];
        float[] pcm = nativeDecode(handle, completeAc3Frame, metadata);
        if (pcm == null || metadata[2] != 1536 || metadata[1] != 48000
                || !(metadata[0] == 1 || metadata[0] == 2 || metadata[0] == 6)
                || pcm.length != metadata[2] * metadata[0])
            throw new IOException("Decoder AC-3 devolveu formato inesperado.");
        return new DecodedFrame(pcm, metadata);
    }

    /** Reuses the audio worker's PCM buffer; returns the exact channel count. */
    public synchronized int decodeInto(byte[] completeAc3Frame,float[] destination) throws IOException {
        if(handle==0)throw new IOException("Decoder AC-3 fechado.");
        if(completeAc3Frame==null||completeAc3Frame.length<128||completeAc3Frame.length>2560
                ||destination==null||destination.length<1536*6)throw new IOException("Buffers AC-3 fora do limite.");
        nativeDecodeInto(handle,completeAc3Frame,liveMetadata,destination);
        int channels=liveMetadata[0];
        if(liveMetadata[2]!=1536||liveMetadata[1]!=48000||!(channels==1||channels==2||channels==6))
            throw new IOException("Decoder AC-3 devolveu formato inesperado.");
        return channels;
    }

    /** Forget overlap history after a discontinuity or an invalid encoded frame. */
    public synchronized void reset() {
        if (handle != 0) nativeReset(handle);
    }

    @Override public synchronized void close() {
        if (handle != 0) { nativeDestroy(handle); handle = 0; }
    }

    private static native long nativeCreate() throws IOException;
    private static native float[] nativeDecode(long handle, byte[] frame, int[] metadata) throws IOException;
    private static native void nativeDecodeInto(long handle,byte[] frame,int[] metadata,float[] destination) throws IOException;
    private static native void nativeReset(long handle);
    private static native void nativeDestroy(long handle);
}
