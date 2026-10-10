package br.com.sistema51.a34.room;

import br.com.sistema51.a34.dsp.AudioProfile;
import java.util.concurrent.atomic.AtomicReference;

/** Control publishes a ticket; the existing audio thread owns its cursor. No USB opens. */
public final class RoomPlayback {
    private static final int FADE=480;
    public static final class Ticket {
        public final String id;
        public final RoomTestSignal signal;
        public final long expiresNanos;
        private volatile int cursor;
        private volatile String state="queued";
        Ticket(String id,RoomTestSignal signal,long now){
            this.id=id;this.signal=signal;expiresNanos=now+25_000_000_000L;
        }
        public String state(){return state;}
        public int renderedFrames(){return cursor;}
    }
    private final AtomicReference<Ticket> requested=new AtomicReference<>();
    private Ticket last;
    private double mix;
    public synchronized Ticket begin(String id,int channel,AudioProfile profile){
        Ticket current=requested.get();
        if(current!=null&&!("done".equals(current.state)||"cancelled".equals(current.state)||"expired".equals(current.state)))
            throw new IllegalStateException("Uma medição já está tocando.");
        float gain=.1f*Math.min(.316f,profile.getMasterGain()*profile.getChannelTrim(channel));
        if(profile.isMuted()||profile.isBypass()||gain<.0005f)
            throw new IllegalStateException("Retome o som, desative bypass e ajuste um nível audível antes de medir.");
        Ticket next=new Ticket(id,new RoomTestSignal(channel,gain),System.nanoTime());
        requested.set(next);return next;
    }
    public synchronized void cancel(){
        Ticket current=requested.getAndSet(null);
        if(current!=null&&!"done".equals(current.state))current.state="cancelled";
    }
    public Ticket ticket(){return requested.get();}
    /** Called AFTER normal DSP, BEFORE channel routing. Program DSP keeps running. */
    public void render(float[] output,int frames,AudioProfile profile){
        Ticket next=requested.get();
        if(next!=null&&(System.nanoTime()>next.expiresNanos||profile.isMuted())){
            next.state=profile.isMuted()?"cancelled":"expired";requested.compareAndSet(next,null);next=null;
        }
        if(profile.isMuted()){mix=0;last=null;return;}
        if(next!=last&&next!=null){last=next;mix=0;}
        if((next==null||"done".equals(next.state))&&mix==0)return;
        Ticket sourceTicket=next==null?last:next;
        double safeGain=sourceTicket==null?0:Math.min(1,.1*Math.min(.316,profile.getMasterGain()*profile.getChannelTrim(sourceTicket.signal.channel))/sourceTicket.signal.gain);
        int cursor=sourceTicket==null?0:sourceTicket.cursor;
        boolean allowed=next!=null&&!"cancelled".equals(next.state)&&!"expired".equals(next.state);
        if(allowed&&cursor<next.signal.frames())next.state="playing";
        for(int f=0;f<frames;f++){
            boolean playing=allowed&&cursor<next.signal.frames();
            if(playing)mix=Math.min(1,mix+1.0/FADE);
            else mix=Math.max(0,mix-1.0/FADE);
            float test=sourceTicket==null?0:sourceTicket.signal.sample(cursor);
            if(sourceTicket!=null&&cursor<sourceTicket.signal.frames())cursor++;
            for(int c=0;c<6;c++)output[f*6+c]=(float)(output[f*6+c]*(1-mix)
                    +(sourceTicket!=null&&c==sourceTicket.signal.channel?test*mix*safeGain:0));
        }
        if(sourceTicket!=null)sourceTicket.cursor=cursor;
        if(next!=null&&allowed&&cursor>=next.signal.frames()&&mix==0&&!"cancelled".equals(next.state))next.state="done";
    }
}
