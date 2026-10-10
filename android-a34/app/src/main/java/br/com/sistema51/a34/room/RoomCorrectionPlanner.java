package br.com.sistema51.a34.room;

import br.com.sistema51.a34.dsp.RoomCorrection;
import java.util.*;

/** Assisted suggestions, never full-band microphone compensation or automatic boosts. */
public final class RoomCorrectionPlanner {
    private RoomCorrectionPlanner(){}
    public static RoomCorrection suggest(Collection<RoomMeasurementAnalyzer.Measurement> measurements) {
        RoomCorrection.Filter[][] filters=new RoomCorrection.Filter[6][];
        for(int channel=0;channel<6;channel++){
            Map<Integer,RoomMeasurementAnalyzer.Measurement> positions=new HashMap<>();
            for(RoomMeasurementAnalyzer.Measurement m:measurements)if(m.channel==channel&&m.rawCapture)positions.put(m.position,m);
            List<RoomCorrection.Filter> selected=new ArrayList<>();
            if(positions.size()>=3){
                int bands=channel==3?11:RoomTestSignal.BASS.length;
                double[] prominence=new double[bands];
                for(int b=2;b<bands-2;b++){
                    double least=Double.POSITIVE_INFINITY;
                    for(RoomMeasurementAnalyzer.Measurement m:positions.values()){
                        if(!Double.isFinite(m.db[b])||!Double.isFinite(m.db[b-2])||!Double.isFinite(m.db[b+2])){least=0;break;}
                        if(!Double.isFinite(m.db[b-1])||!Double.isFinite(m.db[b+1])
                                ||m.db[b]<Math.max(m.db[b-1],m.db[b+1])+.75){least=0;break;}
                        // A local peak must survive EACH position, not only an average.
                        double baseline=.5*(m.db[b-2]+m.db[b+2]);
                        least=Math.min(least,m.db[b]-baseline);
                    }
                    prominence[b]=least;
                }
                boolean[] covered=new boolean[bands];
                for(int k=0;k<RoomCorrection.MAX_FILTERS;k++){
                    int best=-1;double score=3.5;
                    for(int b=2;b<bands-2;b++)if(!covered[b]&&prominence[b]>score){score=prominence[b];best=b;}
                    if(best<0)break;
                    selected.add(new RoomCorrection.Filter((float)RoomTestSignal.BASS[best],(float)-Math.min(6,score*.6),3));
                    for(int b=Math.max(0,best-2);b<=Math.min(bands-1,best+2);b++)covered[b]=true;
                }
            }
            filters[channel]=selected.toArray(new RoomCorrection.Filter[0]);
        }
        // A preview is disabled until the listener explicitly applies it.
        return new RoomCorrection(false,filters);
    }
}
