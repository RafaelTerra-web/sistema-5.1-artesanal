package br.com.sistema51.a34.service;

/** Bounded retry scheduling; explicit Stop always wins over automatic detection. */
public final class OpticalAutoStartPolicy {
    private boolean enabled,paused;
    private long nextAttempt;
    private int failures;
    private long healthySince=-1;
    public OpticalAutoStartPolicy(boolean enabled,boolean paused){this.enabled=enabled;this.paused=paused;}
    public synchronized boolean enabled(){return enabled;}
    public synchronized boolean paused(){return paused;}
    public synchronized void configure(boolean enabled){this.enabled=enabled;paused=false;nextAttempt=0;failures=0;healthySince=-1;}
    public synchronized void pause(){paused=true;}
    public synchronized void resume(){paused=false;nextAttempt=0;failures=0;healthySince=-1;}
    public synchronized boolean shouldStart(long now,boolean hardwareReady,boolean busy,boolean signalReady){
        if(signalReady){if(healthySince<0)healthySince=now;if(now-healthySince>=10000){failures=0;nextAttempt=now+1000;}}
        else healthySince=-1;
        if(enabled&&!busy&&!signalReady&&failures>=3){paused=true;return false;}
        if(!enabled||paused||!hardwareReady||busy||now<nextAttempt)return false;
        nextAttempt=now+Math.min(30000,1000L<<Math.min(failures++,5));
        return true;
    }
}
