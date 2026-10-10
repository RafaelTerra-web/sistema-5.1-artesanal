package br.com.sistema51.a34.room;

import br.com.sistema51.a34.dsp.RoomCorrection;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public final class RoomMeasurementTest {
    private byte[] recording(RoomTestSignal signal,int prefix,double microphoneGain){
        int n=prefix+signal.frames()+24000;byte[] pcm=new byte[n*2];
        for(int f=0;f<signal.frames();f++){
            int value=(int)Math.round(signal.sample(f)*microphoneGain*32767);
            pcm[(f+prefix)*2]=(byte)value;pcm[(f+prefix)*2+1]=(byte)(value>>>8);
        }
        return pcm;
    }
    @Test public void analyzesUnknownNetworkStartWithoutClaimingAcousticDelay(){
        RoomTestSignal signal=new RoomTestSignal(0,.01f);
        byte[] pcm=recording(signal,17000,.3);
        RoomMeasurementAnalyzer.Measurement m=RoomMeasurementAnalyzer.analyze(pcm,pcm.length,signal,0,true);
        assertTrue(m.markerCorrelation>.9);
        assertEquals((17000+24000)/48.0,m.markerOffsetMs,1);
        for(double db:m.db)assertEquals(20*Math.log10(.3),db,.08);
        assertEquals(signal.toneCount(),m.db.length);
    }
    @Test public void supportsSubwooferWithoutOutOfBandTones(){
        RoomTestSignal signal=new RoomTestSignal(3,.01f);byte[] pcm=recording(signal,8000,.2);
        RoomMeasurementAnalyzer.Measurement m=RoomMeasurementAnalyzer.analyze(pcm,pcm.length,signal,1,true);
        assertEquals(11,m.db.length);for(double value:m.db)assertEquals(20*Math.log10(.2),value,.1);
        assertTrue(signal.frequency(10)<140);
    }
    @Test public void rejectsSaturation(){
        RoomTestSignal signal=new RoomTestSignal(0,.01f);byte[] pcm=recording(signal,8000,.2);
        for(int i=0;i<20;i++){pcm[i*2]=(byte)255;pcm[i*2+1]=127;}
        try{RoomMeasurementAnalyzer.analyze(pcm,pcm.length,signal,0,true);fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("saturou"));}
    }
    @Test public void rejectsNoMarkerAndTruncatedToneSequence(){
        RoomTestSignal signal=new RoomTestSignal(0,.01f);
        try{RoomMeasurementAnalyzer.analyze(new byte[100000],100000,signal,0,true);fail();}catch(IllegalArgumentException expected){}
        byte[] complete=recording(signal,8000,.3);
        try{RoomMeasurementAnalyzer.analyze(complete,6*48000*2,signal,0,true);fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("terminou"));}
    }
    private RoomMeasurementAnalyzer.Measurement measured(int channel,int position,boolean raw,int band,double db){
        double[] levels=new double[channel==3?11:20];levels[band]=db;
        return new RoomMeasurementAnalyzer.Measurement(channel,position,raw,levels,-60,.9,800);
    }
    @Test public void proposesOnlyRepeatedCutsAtThreeDistinctPositions(){
        List<RoomMeasurementAnalyzer.Measurement> list=new ArrayList<>();
        for(int p=0;p<3;p++)list.add(measured(2,p,true,7,8-p));
        RoomCorrection r=RoomCorrectionPlanner.suggest(list);
        assertFalse(r.isEnabled());assertEquals(1,r.count(2));assertEquals(89.797,r.filter(2,0).frequencyHz,.01);
        assertTrue(r.filter(2,0).gainDb<0);
        for(int c=0;c<6;c++)if(c!=2)assertEquals(0,r.count(c));
    }
    @Test public void rejectsDuplicatesProcessedCaptureValleysAndSpatiallyUnstablePeaks(){
        assertEquals(0,RoomCorrectionPlanner.suggest(Arrays.asList(measured(0,0,true,7,8),measured(0,0,true,7,8),measured(0,0,true,7,8))).count(0));
        assertEquals(0,RoomCorrectionPlanner.suggest(Arrays.asList(measured(0,0,false,7,8),measured(0,1,false,7,8),measured(0,2,false,7,8))).count(0));
        assertEquals(0,RoomCorrectionPlanner.suggest(Arrays.asList(measured(0,0,true,7,-8),measured(0,1,true,7,-8),measured(0,2,true,7,-8))).count(0));
        assertEquals(0,RoomCorrectionPlanner.suggest(Arrays.asList(measured(0,0,true,7,8),measured(0,1,true,7,8),measured(0,2,true,7,0))).count(0));
    }
    @Test public void doesNotCorrectUnusableBands(){
        List<RoomMeasurementAnalyzer.Measurement> list=new ArrayList<>();
        for(int p=0;p<3;p++){RoomMeasurementAnalyzer.Measurement m=measured(0,p,true,7,8);m.db[5]=Double.NaN;list.add(m);}
        assertEquals(0,RoomCorrectionPlanner.suggest(list).count(0));
    }
}
