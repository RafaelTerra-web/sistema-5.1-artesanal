package br.com.sistema51.a34.service;
import org.junit.Test;
import static org.junit.Assert.*;
public final class OpticalAutoStartPolicyTest {
    @Test public void readinessStopAndResume(){
        OpticalAutoStartPolicy p=new OpticalAutoStartPolicy(true,false);
        assertFalse(p.shouldStart(0,false,false,false));assertFalse(p.shouldStart(0,true,true,false));
        assertTrue(p.shouldStart(0,true,false,false));assertFalse(p.shouldStart(400,true,false,false));
        p.pause();assertFalse(p.shouldStart(40000,true,false,false));p.resume();assertTrue(p.shouldStart(40000,true,false,false));
        p.configure(false);assertFalse(p.shouldStart(90000,true,false,false));
    }
    @Test public void failedTransportRetriesAreBoundedAndHealthySignalResetsBackoff(){
        OpticalAutoStartPolicy p=new OpticalAutoStartPolicy(true,false);
        for(long time:new long[]{0,1000,3000}){
            assertTrue(p.shouldStart(time,true,false,false));assertFalse(p.shouldStart(time+999,true,false,false));
        }
        assertFalse(p.shouldStart(5000,true,false,false));assertTrue(p.paused());
        p.resume();assertTrue(p.shouldStart(10000,true,false,false));
        assertFalse(p.shouldStart(11000,true,true,true));assertFalse(p.shouldStart(21000,true,true,true));
        assertTrue(p.shouldStart(22000,true,false,false));
    }
}
