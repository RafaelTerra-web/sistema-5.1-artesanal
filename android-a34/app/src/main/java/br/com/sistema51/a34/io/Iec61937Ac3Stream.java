package br.com.sistema51.a34.io;

import java.io.IOException;
import java.util.Arrays;

/** Bounded incremental IEC61937 parser. Unknown carrier bytes are never PCM. */
public final class Iec61937Ac3Stream {
    private static final int[] BITRATES={32,40,48,56,64,80,96,112,128,160,192,224,256,320,384,448,512,576,640};
    private static final int[] CHANNELS={2,1,2,3,3,4,4,5};
    private final byte[] bytes=new byte[32768];
    private int used;
    private long base, lastBurst=-1;
    public long validFrames, discontinuities, skippedBytes, crcErrors;
    private boolean pendingDiscontinuity;
    public static final class Frame {
        public final byte[] ac3;
        public final int channels;
        public final boolean discontinuity;
        Frame(byte[] ac3,int channels,boolean discontinuity){this.ac3=ac3;this.channels=channels;this.discontinuity=discontinuity;}
    }
    public void append(byte[] source,int count)throws IOException{
        if(count<0||count>source.length||count>bytes.length-used)throw new IOException("Buffer óptico excedido.");
        System.arraycopy(source,0,bytes,used,count);used+=count;
    }
    private void discard(int count){System.arraycopy(bytes,count,bytes,0,used-count);used-=count;base+=count;}
    public Frame poll()throws IOException{
        int offset=0;
        while(offset+4<=used){
            int a=bytes[offset]&255,b=bytes[offset+1]&255,c=bytes[offset+2]&255,d=bytes[offset+3]&255;
            boolean little=a==0x72&&b==0xf8&&c==0x1f&&d==0x4e;
            boolean big=a==0xf8&&b==0x72&&c==0x4e&&d==0x1f;
            if((!little&&!big)||((base+offset)&3)!=0){offset++;continue;}
            if(offset+8>used){discard(offset);return null;}
            int pc=word(offset+4,little),bits=word(offset+6,little);
            int type=pc&0xff;
            if(type==0||type==3){offset+=8;continue;} // IEC null/pause burst.
            if(type!=1)throw new IOException("Portadora óptica não suportada: IEC61937 tipo "+type+". Use Fire TV DD+ / Bravia Auto 1 com DD+ Out desligado.");
            if(bits==0||(bits&15)!=0||bits/8>3840)throw new IOException("Comprimento IEC61937 AC-3 inválido.");
            int size=bits/8;
            if(offset+8+size>used){discard(offset);return null;}
            byte[] payload=Arrays.copyOfRange(bytes,offset+8,offset+8+size);
            if(little)for(int i=0;i<size;i+=2){byte swap=payload[i];payload[i]=payload[i+1];payload[i+1]=swap;}
            long position=base+offset;
            int channels;
            try { channels=validateAc3(payload); }
            catch(InvalidCrcException corrupted) {
                /* A single bad IEC burst is an erasure, not a reason to kill a live route.
                   Keep alignment from the declared burst length and gate/reset at the next
                   valid frame. Unsupported formats and malformed headers still fail closed. */
                crcErrors++;discontinuities++;pendingDiscontinuity=true;lastBurst=position;
                skippedBytes+=offset;discard(offset+8+size);continue;
            }
            boolean gap=lastBurst>=0&&position-lastBurst!=6144;
            if(gap)discontinuities++;
            lastBurst=position;validFrames++;skippedBytes+=offset;
            boolean discontinuity=gap||pendingDiscontinuity;pendingDiscontinuity=false;
            discard(offset+8+size);
            return new Frame(payload,channels,discontinuity);
        }
        int removable=Math.max(0,used-3);skippedBytes+=removable;discard(removable);return null;
    }
    private int word(int offset,boolean little){int a=bytes[offset]&255,b=bytes[offset+1]&255;return little?a|(b<<8):(a<<8)|b;}
    public static int validateAc3(byte[] data)throws IOException{
        if(data.length<8||(data[0]&255)!=11||(data[1]&255)!=119)throw new IOException("Sync AC-3 ausente.");
        int code=data[4]&63,rate=(data[4]&255)>>>6,bsid=(data[5]&255)>>>3;
        if(rate!=0||bsid>8||code>37||BITRATES[code/2]*4!=data.length)throw new IOException("AC-3 exige quadro completo a 48 kHz.");
        int acmod=(data[6]&255)>>>5,bit=51;
        if((acmod&1)!=0&&acmod!=1)bit+=2;if((acmod&4)!=0)bit+=2;if(acmod==2)bit+=2;
        int lfe=((data[bit/8]&255)>>>(7-bit%8))&1;
        if(!((acmod==1&&lfe==0)||(acmod==2&&lfe==0)||(acmod==7&&lfe==1)))throw new IOException("Mapa AC-3 requer mono, estéreo ou 5.1 completo.");
        int crc=0;
        for(int i=2;i<data.length;i++){crc^=(data[i]&255)<<8;for(int j=0;j<8;j++)crc=((crc<<1)^((crc&0x8000)!=0?0x8005:0))&65535;}
        if(crc!=0)throw new InvalidCrcException();
        return CHANNELS[acmod]+lfe;
    }
    private static final class InvalidCrcException extends IOException {
        private static final long serialVersionUID=1L;
        InvalidCrcException(){super("CRC AC-3 inválido.");}
    }
}
