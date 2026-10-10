package br.com.sistema51.a34.room;

import br.com.sistema51.a34.dsp.AudioProfile;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public final class RoomPlaybackTest {
    private AudioProfile profile(){return AudioProfile.defaultProfile().toBuilder().masterGain(.5f).build();}
    @Test public void defaultDoesNotModifyLiveOutput(){
        RoomPlayback playback=new RoomPlayback();float[] data=new float[480*6];Arrays.fill(data,.123456f);float[] before=data.clone();
        playback.render(data,480,profile());assertArrayEquals(before,data,0);
    }
    @Test public void testIsIsolatedBoundedAndRestoresProgram(){
        RoomPlayback playback=new RoomPlayback();RoomPlayback.Ticket ticket=playback.begin("test",2,profile());
        float[] block=new float[480*6];
        boolean heard=false;
        for(int chunk=0;chunk<ticket.signal.frames()/480+5;chunk++){
            Arrays.fill(block,0);playback.render(block,480,profile());
            for(int f=0;f<480;f++)for(int c=0;c<6;c++){
                assertTrue(Math.abs(block[f*6+c])<=.032f);
                if(c!=2)assertEquals(0,block[f*6+c],0);else heard|=Math.abs(block[f*6+c])>.01;
            }
        }
        assertTrue(heard);assertEquals("done",ticket.state());
        Arrays.fill(block,.15f);playback.render(block,480,profile());for(float s:block)assertEquals(.15f,s,0);
    }
    @Test public void muteAndCancellationNeverLeaveTestRunning(){
        RoomPlayback playback=new RoomPlayback();RoomPlayback.Ticket ticket=playback.begin("test",0,profile());
        float[] block=new float[480*6];playback.render(block,480,profile());
        playback.render(block,480,profile().toBuilder().muted(true).build());assertEquals("cancelled",ticket.state());
        assertNull(playback.ticket());
        playback.begin("another",0,profile());playback.cancel();assertNull(playback.ticket());
    }
    @Test public void guardsSilenceBypassAndConcurrentTests(){
        RoomPlayback playback=new RoomPlayback();
        try{playback.begin("x",0,profile().toBuilder().muted(true).build());fail();}catch(IllegalStateException expected){}
        try{playback.begin("x",0,profile().toBuilder().bypass(true).build());fail();}catch(IllegalStateException expected){}
        playback.begin("x",0,profile());
        try{playback.begin("y",0,profile());fail();}catch(IllegalStateException expected){}
    }
    @Test public void volumeReductionAlsoReducesTestAndDoesNotChangeProfile(){
        RoomPlayback playback=new RoomPlayback();AudioProfile p=profile();playback.begin("x",0,p);
        float[] block=new float[480*6];AudioProfile reduced=p.toBuilder().masterGain(.01f).build();
        for(int i=0;i<80;i++){Arrays.fill(block,0);playback.render(block,480,reduced);for(float s:block)assertTrue(Math.abs(s)<=.00101);}
        assertEquals(.5,p.getMasterGain(),0);assertEquals(3686,p.getDelaySamples(0));
    }
}
