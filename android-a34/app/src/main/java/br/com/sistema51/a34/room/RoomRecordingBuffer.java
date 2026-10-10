package br.com.sistema51.a34.room;

import java.security.*;
import java.nio.charset.StandardCharsets;

/** Single bounded PCM16 upload. Odd chunks and out-of-order offsets are rejected. */
public final class RoomRecordingBuffer {
    private final byte[] bytes=new byte[RoomTestSignal.MAX_RECORD_SECONDS*48000*2];
    private int size;
    public int size(){return size;}
    public byte[] bytes(){return bytes;}
    public void append(int offset,byte[] chunk){
        if(chunk==null||chunk.length==0||chunk.length>16384||(chunk.length&1)!=0
                ||offset!=size||chunk.length>bytes.length-size)
            throw new IllegalArgumentException("Bloco fora de ordem, incompleto ou além do limite.");
        System.arraycopy(chunk,0,bytes,size,chunk.length);size+=chunk.length;
    }
    public void verify(int expectedSize,String sha256){
        if(expectedSize!=size||size==0||sha256==null||!sha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Gravação incompleta ou hash inválido.");
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(bytes,0,size);
            StringBuilder hex=new StringBuilder();char[] digits="0123456789abcdef".toCharArray();
            for(byte b:digest.digest()){hex.append(digits[(b&255)>>>4]);hex.append(digits[b&15]);}
            if(!MessageDigest.isEqual(hex.toString().getBytes(StandardCharsets.US_ASCII),sha256.getBytes(StandardCharsets.US_ASCII)))
                throw new IllegalArgumentException("A integridade da gravação não foi confirmada.");
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
