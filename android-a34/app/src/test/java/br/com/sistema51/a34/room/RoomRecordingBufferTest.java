package br.com.sistema51.a34.room;

import org.junit.Test;
import java.security.*;
import java.util.*;
import static org.junit.Assert.*;

public final class RoomRecordingBufferTest {
    private String sha(byte[] bytes)throws Exception{
        StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
    @Test public void validatesOrderedChunksAndEndToEndHash()throws Exception{
        RoomRecordingBuffer buffer=new RoomRecordingBuffer();byte[] data={1,2,3,4,5,6};
        buffer.append(0,new byte[]{1,2});buffer.append(2,new byte[]{3,4,5,6});buffer.verify(6,sha(data));assertEquals(6,buffer.size());
        try{buffer.verify(6,sha(new byte[]{1,2}));fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void rejectsDuplicateOddOversizedAndMissingChunks(){
        RoomRecordingBuffer buffer=new RoomRecordingBuffer();buffer.append(0,new byte[2]);
        for(byte[] invalid:new byte[][]{new byte[1],new byte[16386],new byte[0]})
            try{buffer.append(2,invalid);fail();}catch(IllegalArgumentException expected){}
        try{buffer.append(0,new byte[2]);fail();}catch(IllegalArgumentException expected){}
        try{buffer.append(4,new byte[2]);fail();}catch(IllegalArgumentException expected){}
    }
}
