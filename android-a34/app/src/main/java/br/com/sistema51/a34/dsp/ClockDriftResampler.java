package br.com.sistema51.a34.dsp;

/**
 * Continuous six-channel cubic resampling for independent S/PDIF and USB clocks.
 * Ratio means output frames / source frames. A fuller USB queue lowers the ratio.
 * This class is owned by the audio worker; it never changes gain, mute or channel order.
 */
public final class ClockDriftResampler {
    public static final int CHANNELS=6;
    public static final int MAX_INPUT_FRAMES=1536;
    public static final int MAX_OUTPUT_FRAMES=1540;
    public static final int DEFAULT_TARGET_FRAMES=1536;
    public static final double MAX_CORRECTION_PPM=1000;
    private static final double SAMPLE_RATE=48000;
    private static final double STARTUP_SECONDS=2;
    private static final double QUEUE_FILTER_SECONDS=2;
    private static final double CONTROL_SECONDS=.25;
    private static final double PROPORTIONAL_PPM_PER_FRAME=1;
    private static final double INTEGRAL_PPM_PER_FRAME_SECOND=.0125;
    private static final double MAX_SLEW_PPM_PER_SECOND=20;

    private final int targetFrames;
    private final float[] previous=new float[3*CHANNELS];
    private boolean hasPrevious;
    private double sourcePosition;
    private double elapsedSeconds,filteredQueue,controlSeconds,integralPpm,correctionPpm;
    private long inputFrames,outputFrames;

    public ClockDriftResampler(){this(DEFAULT_TARGET_FRAMES);}
    public ClockDriftResampler(int targetQueuedFrames){
        if(targetQueuedFrames<=0)throw new IllegalArgumentException("Target queue must be positive");
        targetFrames=targetQueuedFrames;
        reset();
    }

    /** Clear interpolation and clock history when opening a new stream or changing sources. */
    public void reset(){
        hasPrevious=false;sourcePosition=0;elapsedSeconds=controlSeconds=0;
        filteredQueue=targetFrames;integralPpm=correctionPpm=0;
        inputFrames=outputFrames=0;
        java.util.Arrays.fill(previous,0);
    }

    public double getRatio(){return 1+correctionPpm/1_000_000;}
    public double getCorrectionPpm(){return correctionPpm;}
    public long getInputFrames(){return inputFrames;}
    public long getOutputFrames(){return outputFrames;}

    /**
     * Process complete source frames, retaining the fractional position across calls.
     * queuedFrames is the software USB output queue measured before adding this block.
     * out must have space for MAX_OUTPUT_FRAMES * CHANNELS; it must differ from input.
     * A fractional final frame waits for the next block rather than duplicating a sample.
     */
    public int process(float[] interleaved,int frames,float[] out,int queuedFrames){
        if(frames<0||frames>MAX_INPUT_FRAMES)throw new IllegalArgumentException("Source block must have 0..1536 frames");
        if(interleaved==null||out==null||interleaved==out||interleaved.length<frames*CHANNELS
                ||out.length<MAX_OUTPUT_FRAMES*CHANNELS)throw new IllegalArgumentException("Separate source and output buffers are required");
        if(queuedFrames<0)throw new IllegalArgumentException("Output queue cannot be negative");
        for(int sample=0;sample<frames*CHANNELS;sample++)if(!Float.isFinite(interleaved[sample]))
            throw new IllegalArgumentException("Source PCM contains a non-finite sample");
        if(frames==0)return 0;

        updateClock(frames/SAMPLE_RATE,queuedFrames);
        double sourceStep=1/getRatio();
        int produced=0;
        while(true){
            int left=(int)Math.floor(sourcePosition);
            double fraction=sourcePosition-left;
            // Avoid an artificial one-frame delay caused only by floating point round-off.
            if(fraction<1e-10)fraction=0;
            if(left>=frames||(fraction!=0&&left+2>=frames))break;
            if(left<0&&!hasPrevious)throw new IllegalStateException("Interpolation history is missing");
            if(produced>=MAX_OUTPUT_FRAMES)throw new IllegalStateException("Clock correction exceeded the output bound");
            int destination=produced*CHANNELS;
            for(int channel=0;channel<CHANNELS;channel++){
                double first=sample(interleaved,left,channel);
                if(fraction==0){out[destination+channel]=(float)first;continue;}
                double p0=sample(interleaved,left-1,channel),p2=sample(interleaved,left+1,channel),p3=sample(interleaved,left+2,channel);
                out[destination+channel]=(float)(first+fraction*(.5*(p2-p0)+fraction*(p0-2.5*first+2*p2-.5*p3+fraction*(-.5*p0+1.5*first-1.5*p2+.5*p3))));
            }
            produced++;
            sourcePosition+=sourceStep;
        }
        sourcePosition-=frames;
        int retained=Math.min(3,frames);
        if(retained<3)System.arraycopy(previous,retained*CHANNELS,previous,0,(3-retained)*CHANNELS);
        System.arraycopy(interleaved,(frames-retained)*CHANNELS,previous,(3-retained)*CHANNELS,retained*CHANNELS);
        hasPrevious=true;inputFrames+=frames;outputFrames+=produced;
        return produced;
    }

    private double sample(float[] input,int frame,int channel){
        if(frame>=0)return input[frame*CHANNELS+channel];
        if(!hasPrevious)return input[channel];
        return previous[(3+frame)*CHANNELS+channel];
    }

    private void updateClock(double seconds,int queuedFrames){
        elapsedSeconds+=seconds;
        // Initial decoder bursts and queue priming do not enter the clock controller.
        if(elapsedSeconds<STARTUP_SECONDS)return;
        double alpha=1-Math.exp(-seconds/QUEUE_FILTER_SECONDS);
        filteredQueue+=(queuedFrames-filteredQueue)*alpha;
        controlSeconds+=seconds;
        if(controlSeconds<CONTROL_SECONDS)return;
        double interval=controlSeconds;controlSeconds=0;
        double error=targetFrames-filteredQueue;
        double proposedIntegral=bound(integralPpm+error*INTEGRAL_PPM_PER_FRAME_SECOND*interval);
        double requested=error*PROPORTIONAL_PPM_PER_FRAME+proposedIntegral;
        // Do not accumulate an integral that pushes further into a saturated controller.
        if(Math.abs(requested)<=MAX_CORRECTION_PPM||requested*error<=0)integralPpm=proposedIntegral;
        requested=bound(error*PROPORTIONAL_PPM_PER_FRAME+integralPpm);
        double maximumChange=MAX_SLEW_PPM_PER_SECOND*interval;
        correctionPpm=bound(correctionPpm+Math.max(-maximumChange,Math.min(maximumChange,requested-correctionPpm)));
    }

    private static double bound(double ppm){return Math.max(-MAX_CORRECTION_PPM,Math.min(MAX_CORRECTION_PPM,ppm));}
}
