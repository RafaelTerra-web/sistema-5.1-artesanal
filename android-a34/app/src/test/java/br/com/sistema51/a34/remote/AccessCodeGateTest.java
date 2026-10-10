package br.com.sistema51.a34.remote;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AccessCodeGateTest {
    @Test public void acceptsLeadingZeroCodeAndExpiresSession(){
        AccessCodeGate gate=new AccessCodeGate("0042",0,0,0);
        AccessCodeGate.Result result=gate.unlock("0042",1_000);
        assertEquals(200,result.status);
        assertNotEquals("PIN is exchanged for an unrelated random session token","0042",result.sessionToken);
        assertTrue(gate.authorizes(result.sessionToken,1_001));
        assertFalse(gate.authorizes(result.sessionToken,1_000+AccessCodeGate.SESSION_TTL_MILLIS));
    }

    @Test public void throttlesFiveFailuresAndResetsAfterSuccessfulUnlock(){
        AccessCodeGate gate=new AccessCodeGate("1234",0,0,0);
        for(int i=0;i<4;i++)assertEquals(401,gate.unlock("9999",10_000+i).status);
        AccessCodeGate.Result blocked=gate.unlock("9999",10_004);
        assertEquals(429,blocked.status);
        assertEquals(30_000,blocked.retryAfterMillis);
        assertEquals(429,gate.unlock("1234",10_005).status);
        assertEquals(200,gate.unlock("1234",40_004).status);
        assertEquals(0,gate.failedAttempts());
        assertEquals(0,gate.lockoutLevel());
        assertEquals(0,gate.blockedUntilMillis());
    }

    @Test public void escalatesRepeatedLockouts(){
        AccessCodeGate gate=new AccessCodeGate("1234",0,0,0);
        for(int i=0;i<5;i++)gate.unlock("0000",100+i);
        for(int i=0;i<5;i++)gate.unlock("0000",30_104+i);
        assertEquals(120_000,gate.unlock("1234",30_108).retryAfterMillis);
    }

    @Test public void acceptsOnlyFourAsciiDigits(){
        try{new AccessCodeGate("123",0,0,0);fail("Short PIN accepted");}catch(IllegalArgumentException expected){}
        try{new AccessCodeGate("１２３４",0,0,0);fail("Non-ASCII PIN accepted");}catch(IllegalArgumentException expected){}
    }
}
