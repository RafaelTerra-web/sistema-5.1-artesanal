package br.com.sistema51.a34.remote;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exchanges the visible four-digit LAN code for short-lived high-entropy API sessions. */
final class AccessCodeGate {
    static final long SESSION_TTL_MILLIS=12L*60*60*1000;
    private static final int MAX_FAILURES=5,MAX_SESSIONS=8;
    private static final long[] LOCKOUT_MILLIS={30_000,120_000,600_000,1_800_000,7_200_000};
    private final String code;
    private final SecureRandom random=new SecureRandom();
    private final Map<String,Long> sessions=new LinkedHashMap<>();
    private int failedAttempts,lockoutLevel;
    private long blockedUntilMillis;

    AccessCodeGate(String code,int failedAttempts,int lockoutLevel,long blockedUntilMillis){
        if(code==null||!code.matches("[0-9]{4}"))throw new IllegalArgumentException("O código local precisa ter quatro dígitos.");
        this.code=code;
        this.failedAttempts=Math.max(0,Math.min(MAX_FAILURES-1,failedAttempts));
        this.lockoutLevel=Math.max(0,Math.min(LOCKOUT_MILLIS.length,lockoutLevel));
        this.blockedUntilMillis=Math.max(0,blockedUntilMillis);
    }

    synchronized Result unlock(String candidate,long nowMillis){
        if(blockedUntilMillis>nowMillis)return new Result(429,null,blockedUntilMillis-nowMillis);
        blockedUntilMillis=0;
        byte[] expected=code.getBytes(StandardCharsets.US_ASCII);
        byte[] supplied=(candidate==null?"":candidate).getBytes(StandardCharsets.US_ASCII);
        if(!MessageDigest.isEqual(expected,supplied)){
            failedAttempts++;
            if(failedAttempts>=MAX_FAILURES){
                failedAttempts=0;
                long delay=LOCKOUT_MILLIS[Math.min(lockoutLevel,LOCKOUT_MILLIS.length-1)];
                lockoutLevel=Math.min(lockoutLevel+1,LOCKOUT_MILLIS.length);
                blockedUntilMillis=nowMillis+delay;
                return new Result(429,null,delay);
            }
            return new Result(401,null,0);
        }
        failedAttempts=0;lockoutLevel=0;blockedUntilMillis=0;
        reap(nowMillis);
        while(sessions.size()>=MAX_SESSIONS){Iterator<String> oldest=sessions.keySet().iterator();oldest.next();oldest.remove();}
        String session=randomSession();sessions.put(session,nowMillis+SESSION_TTL_MILLIS);
        return new Result(200,session,SESSION_TTL_MILLIS);
    }

    synchronized boolean authorizes(String session,long nowMillis){
        if(session==null)return false;
        Long expires=sessions.get(session);
        if(expires==null)return false;
        if(nowMillis>=expires){sessions.remove(session);return false;}
        return true;
    }
    synchronized int failedAttempts(){return failedAttempts;}
    synchronized int lockoutLevel(){return lockoutLevel;}
    synchronized long blockedUntilMillis(){return blockedUntilMillis;}

    private void reap(long nowMillis){sessions.entrySet().removeIf(entry->nowMillis>=entry.getValue());}
    private String randomSession(){
        byte[] bytes=new byte[32];random.nextBytes(bytes);char[] hex=new char[bytes.length*2];char[] digits="0123456789abcdef".toCharArray();
        for(int i=0;i<bytes.length;i++){int value=bytes[i]&255;hex[i*2]=digits[value>>>4];hex[i*2+1]=digits[value&15];}
        return new String(hex);
    }
    static final class Result{
        final int status;final String sessionToken;final long retryAfterMillis;
        Result(int status,String sessionToken,long retryAfterMillis){this.status=status;this.sessionToken=sessionToken;this.retryAfterMillis=retryAfterMillis;}
    }
}
