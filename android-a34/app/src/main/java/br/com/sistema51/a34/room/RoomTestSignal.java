package br.com.sistema51.a34.room;

/** One isolated output: acoustic marker, settled sine bursts, then silence. */
public final class RoomTestSignal {
    public static final int RATE=48000, MAX_RECORD_SECONDS=20;
    public static final double LEAD=.5, MARKER=.55, GAP=.4, TONE=.45, REST=.12, TAIL=.5;
    public static final double[] BASS={40,44.898,50.397,56.569,63.496,71.272,80,89.797,100.794,113.137,126.992,142.544,160,179.594,201.587,226.274,250};
    public final int channel;
    public final float gain;
    private final int frameCount;
    public RoomTestSignal(int channel,float gain) {
        if(channel<0||channel>=6||!Float.isFinite(gain)||gain<=0||gain>.032f)
            throw new IllegalArgumentException("Caixa ou nível do teste inválido.");
        this.channel=channel;this.gain=gain;
        frameCount=(int)Math.ceil((LEAD+MARKER+GAP+toneCount()*(TONE+REST)+TAIL)*RATE);
    }
    public int bassCount(){return channel==3?11:BASS.length;}
    public int toneCount(){return bassCount()+(channel==3?0:3);}
    public double frequency(int band){return band<bassCount()?BASS[band]:500*Math.pow(2,band-bassCount());}
    public double toneStart(int band){return LEAD+MARKER+GAP+band*(TONE+REST);}
    public int frames(){return frameCount;}
    public static double marker(double time) {
        if(time<0||time>=MARKER)return 0;
        double k=Math.log(125.0/55)/MARKER;
        double phase=2*Math.PI*55*Math.expm1(k*time)/k;
        double envelope=Math.sin(Math.PI*time/MARKER);
        return Math.sin(phase)*envelope*envelope;
    }
    public float sample(int frame) {
        double t=frame/(double)RATE;
        if(t<LEAD)return 0;
        if(t<LEAD+MARKER)return (float)(gain*marker(t-LEAD));
        double elapsed=t-LEAD-MARKER-GAP;
        if(elapsed<0)return 0;
        int band=(int)(elapsed/(TONE+REST));
        if(band>=toneCount())return 0;
        double local=elapsed-band*(TONE+REST);
        if(local>=TONE)return 0;
        double envelope=Math.min(1,Math.min(local/.025,(TONE-local)/.025));
        return (float)(gain*Math.max(0,envelope)*Math.sin(2*Math.PI*frequency(band)*local));
    }
}
