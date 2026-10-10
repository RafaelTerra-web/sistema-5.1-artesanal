package br.com.sistema51.a34.room;

/** Offline analysis of native A55 mono PCM16, using acoustic rather than Wi-Fi time. */
public final class RoomMeasurementAnalyzer {
    public static final class Measurement {
        public final int channel,position;
        public final boolean rawCapture;
        public final double[] db;
        public final double noiseDb,markerCorrelation,markerOffsetMs;
        Measurement(int channel,int position,boolean rawCapture,double[] db,double noise,double correlation,double offset){
            this.channel=channel;this.position=position;this.rawCapture=rawCapture;
            this.db=db;noiseDb=noise;markerCorrelation=correlation;markerOffsetMs=offset;
        }
    }
    public static Measurement analyze(byte[] pcm,int length,RoomTestSignal signal,int position,boolean rawCapture) {
        if(pcm==null||length<48000||length>pcm.length||(length&1)!=0||length>RoomTestSignal.MAX_RECORD_SECONDS*48000*2)
            throw new IllegalArgumentException("Gravação incompleta ou longa demais.");
        if(position<0||position>4)throw new IllegalArgumentException("Posição inválida.");
        int count=length/2,clips=0;double[] samples=new double[count];
        for(int i=0;i<count;i++){short value=(short)((pcm[2*i]&255)|(pcm[2*i+1]<<8));samples[i]=value/32768.0;if(Math.abs(value)>=32760)clips++;}
        if(clips>4)throw new IllegalArgumentException("O microfone saturou. Reduza o nível do teste e repita.");
        // Low-pass/decimate to 2 kHz; the marker contains only 55–125 Hz.
        int factor=24,markerFrames=(int)(RoomTestSignal.MARKER*2000),smallCount=count/factor;
        double[] small=new double[smallCount],reference=new double[markerFrames];
        for(int i=0;i<small.length;i++){double sum=0;for(int j=0;j<factor;j++)sum+=samples[i*factor+j];small[i]=sum/factor;}
        double referenceEnergy=0;
        for(int i=0;i<reference.length;i++){reference[i]=RoomTestSignal.marker((i+.5)/2000.0);referenceEnergy+=reference[i]*reference[i];}
        int limit=Math.min(5*2000,small.length-markerFrames),best=-1;double score=0,energy=0;
        for(int i=0;i<markerFrames;i++)energy+=small[i]*small[i];
        for(int start=0;start<=limit;start++){
            double correlation=0;
            for(int j=0;j<markerFrames;j++)correlation+=small[start+j]*reference[j];
            double normalized=Math.abs(correlation)/Math.sqrt(Math.max(1e-30,energy*referenceEnergy));
            if(normalized>score){score=normalized;best=start;}
            if(start<limit)energy+=small[start+markerFrames]*small[start+markerFrames]-small[start]*small[start];
        }
        if(best<0||score<.25)throw new IllegalArgumentException("Marcador acústico não encontrado. Confira a caixa, o volume e o ruído.");
        int markerStart=best*factor;
        int noiseEnd=markerStart-(int)(.03*48000),noiseStart=Math.max(0,noiseEnd-(int)(.2*48000));
        if(noiseEnd-noiseStart<4800)throw new IllegalArgumentException("Faltou silêncio antes do marcador.");
        double noisePower=0;
        for(int i=noiseStart;i<noiseEnd;i++)noisePower+=samples[i]*samples[i];
        double noiseRms=Math.sqrt(noisePower/(noiseEnd-noiseStart));
        double[] levels=new double[signal.toneCount()];
        int valid=0;
        for(int band=0;band<levels.length;band++){
            int from=markerStart+(int)Math.round((signal.toneStart(band)-RoomTestSignal.LEAD+.12)*48000);
            int n=(int)(.22*48000);
            if(from<0||from+n>samples.length)throw new IllegalArgumentException("O teste ou a gravação terminou antes de todas as frequências.");
            double real=0,imaginary=0,weight=0,frequency=signal.frequency(band);
            for(int i=0;i<n;i++){
                double w=.5-.5*Math.cos(2*Math.PI*i/(n-1)),angle=2*Math.PI*frequency*i/48000;
                real+=w*samples[from+i]*Math.cos(angle);imaginary-=w*samples[from+i]*Math.sin(angle);weight+=w;
            }
            double amplitude=2*Math.hypot(real,imaginary)/weight;
            if(amplitude<Math.max(1e-5,noiseRms*4))levels[band]=Double.NaN;
            else{levels[band]=20*Math.log10(amplitude/signal.gain);valid++;}
        }
        if(valid<6)throw new IllegalArgumentException("Poucas frequências ficaram acima do ruído. Confira o nível, a caixa e o ambiente.");
        return new Measurement(signal.channel,position,rawCapture,levels,20*Math.log10(Math.max(1e-9,noiseRms)),score,markerStart/48.0);
    }
}
