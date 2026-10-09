package br.com.sistema51.a34.io;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Strict streaming RIFF PCM reader and six-channel float writer. */
public final class WavIO {
    private WavIO() {}
    public static final class Reader implements Closeable {
        private final RandomAccessFile file;
        public final int channels, sampleRate, bits, encoding, channelMask;
        public final long frames;
        private final int bytesPerFrame;
        private long remaining;
        private final byte[] buffer = new byte[480 * 6 * 4];
        public Reader(File input) throws IOException {
            file = new RandomAccessFile(input, "r");
            try {
                if (file.length() < 44 || !fourcc(file).equals("RIFF")) throw new IOException("Esperado arquivo RIFF/WAV.");
                long riffSize = readU32(file);
                if (riffSize + 8 > file.length() || !fourcc(file).equals("WAVE")) throw new IOException("WAV truncado ou inválido.");
                int ch = 0, rate = 0, bit = 0, enc = 0, mask = 0, align = 0; long byteRate = 0, start = -1, size = -1;
                long end = riffSize + 8;
                while (file.getFilePointer() + 8 <= end) {
                    String id = fourcc(file); long chunkSize = readU32(file), pos = file.getFilePointer();
                    if (pos + chunkSize > end) throw new IOException("Chunk WAV truncado.");
                    if (id.equals("fmt ")) {
                        if (chunkSize < 16) throw new IOException("Formato WAV curto.");
                        enc = readU16(file); ch = readU16(file); rate = (int) readU32(file); byteRate = readU32(file); align = readU16(file); bit = readU16(file);
                        if (enc == 65534) {
                            if (chunkSize < 40 || readU16(file) < 22) throw new IOException("WAV extensible inválido.");
                            int valid = readU16(file); mask = (int) readU32(file); enc = readU16(file);
                            byte[] suffix = new byte[14]; file.readFully(suffix);
                            byte[] expected = {0,0,0,0,16,0,(byte)128,0,0,(byte)170,0,56,(byte)155,113};
                            if (!java.util.Arrays.equals(suffix, expected) || (valid != 0 && valid != bit)) throw new IOException("Subtipo WAV não suportado.");
                        }
                    } else if (id.equals("data")) {
                        if (start != -1) throw new IOException("WAV com mais de um chunk data.");
                        start = pos; size = chunkSize;
                    }
                    file.seek(pos + chunkSize + (chunkSize & 1));
                }
                if (!(ch == 1 || ch == 2 || ch == 6) || rate != 48000) throw new IOException("Use WAV de 1, 2 ou 6 canais a 48 kHz.");
                if (!((enc == 1 && bit == 16) || (enc == 3 && bit == 32))) throw new IOException("Use PCM16 ou float32.");
                if (ch == 6 && mask != 0 && mask != 0x3f && mask != 0x60f) throw new IOException("Mapa dos seis canais não suportado.");
                if (ch == 2 && mask != 0 && mask != 3) throw new IOException("Estéreo precisa declarar frontal esquerdo/direito.");
                if (ch == 1 && mask != 0 && mask != 4) throw new IOException("Mono precisa declarar o canal central.");
                if (align != ch * bit / 8 || byteRate != (long) rate * align || start < 0 || size % align != 0) throw new IOException("Alinhamento WAV inválido.");
                channels = ch; sampleRate = rate; bits = bit; encoding = enc; channelMask = mask;
                bytesPerFrame = align; remaining = size; frames = size / align; file.seek(start);
            } catch (Throwable e) { file.close(); if (e instanceof IOException) throw (IOException)e; throw new IOException(e); }
        }
        public int readFrames(float[] output, int maximum) throws IOException {
            if (maximum < 0 || maximum > 480 || output.length < maximum * channels) throw new IllegalArgumentException("Bloco inválido.");
            int count = (int)Math.min(maximum, remaining / bytesPerFrame), bytes = count * bytesPerFrame;
            file.readFully(buffer, 0, bytes); remaining -= bytes;
            ByteBuffer b = ByteBuffer.wrap(buffer, 0, bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count * channels; i++) {
                float value = encoding == 1 ? b.getShort() / 32768.0f : b.getFloat();
                if (!Float.isFinite(value)) throw new IOException("WAV contém valor não finito.");
                output[i] = value;
            }
            return count;
        }
        @Override public void close() throws IOException { file.close(); }
    }
    public static final class Writer implements Closeable {
        private final RandomAccessFile file;
        private final int channels;
        private long bytes;
        private final byte[] scratch = new byte[480 * 6 * 4];
        public Writer(File output, int channels) throws IOException {
            if (!(channels == 1 || channels == 2 || channels == 6)) throw new IllegalArgumentException("Canais inválidos.");
            this.channels = channels; file = new RandomAccessFile(output, "rw"); file.setLength(0);
            ByteBuffer h = ByteBuffer.allocate(68).order(ByteOrder.LITTLE_ENDIAN);
            h.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(60).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(40);
            h.putShort((short)65534).putShort((short)channels).putInt(48000).putInt(48000*channels*4).putShort((short)(channels*4)).putShort((short)32);
            h.putShort((short)22).putShort((short)32).putInt(channels==6?0x60f:channels==2?3:4);
            h.putInt(3).putShort((short)0).putShort((short)16).put(new byte[]{(byte)128,0,0,(byte)170,0,56,(byte)155,113});
            h.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(0); file.write(h.array());
        }
        public void writeFrames(float[] input, int count) throws IOException {
            if (count < 0 || count > 480 || input.length < count*channels) throw new IllegalArgumentException("Bloco inválido.");
            ByteBuffer b = ByteBuffer.wrap(scratch).order(ByteOrder.LITTLE_ENDIAN);
            for (int i=0;i<count*channels;i++) { if (!Float.isFinite(input[i])) throw new IOException("PCM contém valor não finito."); b.putFloat(input[i]); }
            int length=count*channels*4; if (bytes+length > 0xffffffffL-60) throw new IOException("WAV excede limite RIFF.");
            file.write(scratch,0,length);bytes+=length;
        }
        public long frames() { return bytes/(channels*4); }
        @Override public void close() throws IOException {
            try { file.seek(4); writeU32(file,60+bytes);file.seek(64);writeU32(file,bytes); }
            finally { file.close(); }
        }
    }
    private static String fourcc(RandomAccessFile f) throws IOException { byte[] b=new byte[4];f.readFully(b);return new String(b,StandardCharsets.US_ASCII); }
    private static int readU16(RandomAccessFile f) throws IOException { int lo=f.readUnsignedByte();return lo|(f.readUnsignedByte()<<8); }
    private static long readU32(RandomAccessFile f) throws IOException { return (long)readU16(f)|((long)readU16(f)<<16); }
    private static void writeU32(RandomAccessFile f,long value) throws IOException { for(int i=0;i<4;i++) f.write((int)(value>>>(8*i))&255); }
}
