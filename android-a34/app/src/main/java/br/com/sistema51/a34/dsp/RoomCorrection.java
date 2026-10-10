package br.com.sistema51.a34.dsp;

import java.util.Arrays;

/** Immutable output EQ, deliberately restricted to low-frequency cuts. */
public final class RoomCorrection {
    public static final int MAX_FILTERS = 3;
    public static final class Filter {
        public final float frequencyHz, gainDb, q;
        public Filter(float frequencyHz, float gainDb, float q) {
            if (!Float.isFinite(frequencyHz) || frequencyHz < 40 || frequencyHz > 250
                    || !Float.isFinite(gainDb) || gainDb < -6 || gainDb > 0
                    || !Float.isFinite(q) || q < .7f || q > 5)
                throw new IllegalArgumentException("Correção: 40–250 Hz, −6–0 dB, Q 0,7–5.");
            this.frequencyHz=frequencyHz;this.gainDb=gainDb;this.q=q;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Filter)) return false;
            Filter f=(Filter)other;
            return frequencyHz==f.frequencyHz&&gainDb==f.gainDb&&q==f.q;
        }
        @Override public int hashCode() { return Arrays.hashCode(new float[]{frequencyHz,gainDb,q}); }
    }
    private final boolean enabled;
    private final Filter[][] channels;
    public RoomCorrection(boolean enabled, Filter[][] channels) {
        if(channels==null||channels.length!=6)throw new IllegalArgumentException("A correção requer seis canais.");
        this.enabled=enabled;this.channels=new Filter[6][];
        for(int c=0;c<6;c++){
            if(channels[c]==null||channels[c].length>MAX_FILTERS)throw new IllegalArgumentException("Máximo de três filtros por caixa.");
            this.channels[c]=channels[c].clone();
            for(Filter filter:this.channels[c])if(filter==null)throw new IllegalArgumentException("Filtro ausente.");
        }
    }
    public static RoomCorrection empty() { return new RoomCorrection(false,new Filter[][]{{},{},{},{},{},{}}); }
    public boolean isEnabled(){return enabled;}
    public int count(int channel){return channels[AudioProfile.channelIndex(channel)].length;}
    public Filter filter(int channel,int index){return channels[AudioProfile.channelIndex(channel)][index];}
    public RoomCorrection withEnabled(boolean value){return new RoomCorrection(value,channels);}
    @Override public boolean equals(Object other){
        if(!(other instanceof RoomCorrection))return false;
        RoomCorrection r=(RoomCorrection)other;
        return enabled==r.enabled&&Arrays.deepEquals(channels,r.channels);
    }
    @Override public int hashCode(){return 31*Arrays.deepHashCode(channels)+(enabled?1:0);}
}
