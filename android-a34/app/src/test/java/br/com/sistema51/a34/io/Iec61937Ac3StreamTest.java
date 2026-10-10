package br.com.sistema51.a34.io;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.io.IOException;
public final class Iec61937Ac3StreamTest {
    private byte[] frame()throws Exception{
        byte[] all=Files.readAllBytes(Paths.get("src/debug/assets/fixtures/tones-51.ac3"));
        int[] rates={32,40,48,56,64,80,96,112,128,160,192,224,256,320,384,448,512,576,640};
        return Arrays.copyOf(all,rates[(all[4]&63)/2]*4);
    }
    private byte[] burst(byte[] frame,boolean little){
        byte[] out=new byte[6144];int[] pre=little?new int[]{0x72,0xf8,0x1f,0x4e}:new int[]{0xf8,0x72,0x4e,0x1f};
        for(int i=0;i<4;i++)out[i]=(byte)pre[i];int bits=frame.length*8;
        out[little?4:5]=1;out[little?6:7]=(byte)bits;out[little?7:6]=(byte)(bits>>>8);
        for(int i=0;i<frame.length;i++)out[8+i]=frame[little?(i^1):i];return out;
    }
    @Test public void everyFragmentBoundaryAndEndian()throws Exception{
        for(boolean little:new boolean[]{false,true})for(int step:new int[]{1,3,191,200,4096}){
            Iec61937Ac3Stream parser=new Iec61937Ac3Stream();byte[] data=burst(frame(),little);int found=0;
            for(int n=0;n<data.length;n+=step){parser.append(Arrays.copyOfRange(data,n,Math.min(data.length,n+step)),Math.min(step,data.length-n));
                Iec61937Ac3Stream.Frame item;while((item=parser.poll())!=null){assertEquals(6,item.channels);assertArrayEquals(frame(),item.ac3);found++;}}
            assertEquals(1,found);
        }
    }
    @Test public void regularSpacingAndGap()throws Exception{
        Iec61937Ac3Stream parser=new Iec61937Ac3Stream();byte[] data=burst(frame(),true);
        parser.append(data,data.length);assertFalse(parser.poll().discontinuity);parser.poll();
        parser.append(data,data.length);assertFalse(parser.poll().discontinuity);parser.poll();
        parser.append(new byte[4],4);parser.append(data,data.length);assertTrue(parser.poll().discontinuity);assertEquals(1,parser.discontinuities);
    }
    @Test public void badCrcBurstIsDroppedAndNextFrameRecovers()throws Exception{
        byte[] bad=burst(frame(),true);bad[99]^=1;byte[] good=burst(frame(),true),data=new byte[bad.length+good.length];
        System.arraycopy(bad,0,data,0,bad.length);System.arraycopy(good,0,data,bad.length,good.length);
        Iec61937Ac3Stream p=new Iec61937Ac3Stream();p.append(data,data.length);Iec61937Ac3Stream.Frame recovered=p.poll();
        assertNotNull(recovered);assertArrayEquals(frame(),recovered.ac3);assertTrue(recovered.discontinuity);
        assertEquals(1,p.crcErrors);assertEquals(1,p.discontinuities);assertEquals(1,p.validFrames);
    }
    @Test public void badCrcAloneProducesNoFrameAndDoesNotThrow()throws Exception{
        byte[] data=burst(frame(),true);data[99]^=1;Iec61937Ac3Stream p=new Iec61937Ac3Stream();p.append(data,data.length);
        assertNull(p.poll());assertEquals(1,p.crcErrors);assertEquals(0,p.validFrames);
    }
    @Test public void unknownCarrierNeverReturned()throws Exception{Iec61937Ac3Stream p=new Iec61937Ac3Stream();byte[] unknown=new byte[4096];Arrays.fill(unknown,(byte)0x34);for(int i=0;i<50;i++){p.append(unknown,unknown.length);assertNull(p.poll());}assertEquals(0,p.validFrames);}
}
