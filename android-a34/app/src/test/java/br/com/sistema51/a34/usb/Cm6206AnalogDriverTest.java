package br.com.sistema51.a34.usb;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

/** Hardware-free restoration checks; these do not validate Android HID access. */
public final class Cm6206AnalogDriverTest {
    private static final class Mock implements Cm6206AnalogDriver.Registers {
        int value=0x6004, writes, baseline=-1;
        boolean journalFails, writeThrowsAfterApply, refuseWrite;
        @Override public int read(int register) { assertEquals(2,register);return value; }
        @Override public void journal(int original)throws IOException {
            if(journalFails)throw new IOException("Disk failure");baseline=original;
        }
        @Override public void clearJournal(){baseline=-1;}
        @Override public void writeDriver(int next)throws IOException {
            assertNotEquals(-1,baseline);assertEquals(value&0x7fff,next&0x7fff);
            writes++;if(!refuseWrite)value=next;
            if(writeThrowsAfterApply){writeThrowsAfterApply=false;throw new IOException("Unknown completion");}
        }
    }
    @Test public void enablesThenRestoresOnlyOwnedBit()throws Exception {
        Mock m=new Mock();Cm6206AnalogDriver driver=new Cm6206AnalogDriver(m);
        driver.enable();assertEquals(0xe004,m.value);assertEquals(0x6004,m.baseline);
        m.value^=0x10;driver.close();assertEquals(0x6014,m.value);
        assertEquals(-1,m.baseline);driver.close();assertEquals(2,m.writes);
    }
    @Test public void alreadyEnabledNeedsNoWrites()throws Exception {
        Mock m=new Mock();m.value=0xe004;
        try(Cm6206AnalogDriver driver=new Cm6206AnalogDriver(m)){driver.enable();}
        assertEquals(0,m.writes);assertEquals(0xe004,m.value);
    }
    @Test public void journalFailurePreventsWrite()throws Exception {
        Mock m=new Mock();m.journalFails=true;
        try(Cm6206AnalogDriver driver=new Cm6206AnalogDriver(m)) {
            try{driver.enable();fail();}catch(IOException expected){}
        }
        assertEquals(0,m.writes);assertEquals(0x6004,m.value);
    }
    @Test public void uncertainWriteStillRestores()throws Exception {
        Mock m=new Mock();m.writeThrowsAfterApply=true;
        try(Cm6206AnalogDriver driver=new Cm6206AnalogDriver(m)) {
            try{driver.enable();fail();}catch(IOException expected){}
            assertEquals(0xe004,m.value);
        }
        assertEquals(0x6004,m.value);assertEquals(-1,m.baseline);
    }
    @Test public void failedRestorationKeepsJournalForRetry()throws Exception {
        Mock m=new Mock();Cm6206AnalogDriver driver=new Cm6206AnalogDriver(m);driver.enable();
        m.refuseWrite=true;try{driver.close();fail();}catch(IOException expected){}
        assertEquals(0x6004,m.baseline);m.refuseWrite=false;driver.close();assertEquals(0x6004,m.value);
        assertEquals(-1,m.baseline);
    }
}
