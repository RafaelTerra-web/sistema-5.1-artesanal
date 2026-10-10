package br.com.sistema51.a34.dsp;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ClockDriftResamplerTest {
    private static float[] output(){return new float[ClockDriftResampler.MAX_OUTPUT_FRAMES*6];}
    private static float[] constant(int frames){
        float[] input=new float[frames*6];
        for(int frame=0;frame<frames;frame++)for(int channel=0;channel<6;channel++)input[frame*6+channel]=(channel-2)*.125f;
        return input;
    }

    @Test public void preservesEachConstantChannelAndReset(){
        ClockDriftResampler resampler=new ClockDriftResampler();
        float[] input=constant(1536),out=output();
        for(int block=0;block<1200;block++){
            int frames=resampler.process(input,1536,out,0);
            for(int sample=0;sample<frames*6;sample++)assertEquals(input[sample%6],out[sample],0);
        }
        assertTrue(resampler.getCorrectionPpm()>0);
        assertTrue(resampler.getOutputFrames()>resampler.getInputFrames());
        resampler.reset();
        assertEquals(1,resampler.getRatio(),0);assertEquals(0,resampler.getInputFrames());assertEquals(0,resampler.getOutputFrames());
        assertEquals(1536,resampler.process(input,1536,out,1536));
    }

    @Test public void fractionalSineIsContinuousAcrossUnevenBlocks(){
        ClockDriftResampler whole=new ClockDriftResampler(),split=new ClockDriftResampler();
        float[] zero=new float[1536*6],discard=output();
        // Establish the same stable non-unit ratio and interpolation phase in both instances.
        for(int block=0;block<4000;block++){whole.process(zero,1536,discard,0);split.process(zero,1536,discard,0);}
        assertEquals(1000,whole.getCorrectionPpm(),1e-7);
        float[] sine=new float[1536*6];
        for(int frame=0;frame<1536;frame++)for(int channel=0;channel<6;channel++)
            sine[frame*6+channel]=(float)(Math.sin(frame*.19+channel*.37)*.7);
        float[] expected=output();int expectedFrames=whole.process(sine,1536,expected,0);
        float[] actual=new float[ClockDriftResampler.MAX_OUTPUT_FRAMES*6];int copied=0,source=0;
        for(int frames:new int[]{227,709,600}){
            float[] part=new float[frames*6];System.arraycopy(sine,source*6,part,0,part.length);
            float[] partOutput=output();int count=split.process(part,frames,partOutput,0);
            System.arraycopy(partOutput,0,actual,copied*6,count*6);copied+=count;source+=frames;
        }
        assertEquals(expectedFrames,copied);
        for(int sample=0;sample<copied*6;sample++)assertEquals(expected[sample],actual[sample],2e-6f);
    }

    @Test public void burstJitterAndStartupDoNotCreateLargeCorrection(){
        ClockDriftResampler resampler=new ClockDriftResampler();float[] input=constant(1536),out=output();
        for(int block=0;block<60;block++)resampler.process(input,1536,out,0);
        assertEquals(0,resampler.getCorrectionPpm(),0);
        double previous=0;
        for(int block=0;block<1800;block++){
            // Alternating burst-size occupancy jitter has zero average clock drift.
            resampler.process(input,1536,out,block%2==0?1280:1792);
            assertTrue(Math.abs(resampler.getCorrectionPpm()-previous)<=20*.256+1e-9);
            previous=resampler.getCorrectionPpm();
        }
        assertTrue(Math.abs(resampler.getCorrectionPpm())<25);
    }

    @Test public void followsPositiveAndNegativeClockDriftWithoutLosingFrames(){
        simulateDrift(350);simulateDrift(-350);
    }

    private static void simulateDrift(double sourcePpm){
        ClockDriftResampler resampler=new ClockDriftResampler();float[] input=constant(1536),out=output();
        double queued=1536,consumedPerBurst=1536/(1+sourcePpm/1_000_000);
        long produced=0;int blocks=18750; // Ten minutes of continuous input, without sleeping.
        for(int block=0;block<blocks;block++){
            int frames=resampler.process(input,1536,out,(int)Math.round(queued));produced+=frames;
            queued+=frames-consumedPerBurst;
            assertTrue("Queue underflow for "+sourcePpm,queued>0);
            assertTrue("Queue overflow for "+sourcePpm,queued<4608);
            assertTrue(Math.abs(resampler.getCorrectionPpm())<=1000);
        }
        assertEquals("Clock correction sign and magnitude",-sourcePpm,resampler.getCorrectionPpm(),15);
        assertEquals("Stable queue",1536,queued,20);
        assertEquals(produced,resampler.getOutputFrames());
        assertEquals((long)blocks*1536,resampler.getInputFrames());
        assertEquals("Frame accounting",1536+produced-blocks*consumedPerBurst,queued,.001);
    }

    @Test public void correctionIsBoundedAndNonFiniteInputIsRejectedBeforeStateChanges(){
        ClockDriftResampler resampler=new ClockDriftResampler();float[] input=constant(1536),out=output();
        for(int block=0;block<4000;block++)resampler.process(input,1536,out,4608);
        assertEquals(-1000,resampler.getCorrectionPpm(),1e-7);
        long before=resampler.getInputFrames();input[42]=Float.NaN;
        try{resampler.process(input,1536,out,1536);fail("NaN accepted");}catch(IllegalArgumentException expected){}
        assertEquals(before,resampler.getInputFrames());
    }

    @Test public void cubicKeepsMoreHighFrequencyEnergyThanLinearAtClockDrift(){
        ClockDriftResampler r=new ClockDriftResampler();float[] in=new float[1536*6],out=output();
        for(int block=0;block<4000;block++)r.process(in,1536,out,0);
        double energy=0;long samples=0,source=0;
        for(int block=0;block<100;block++){
            for(int f=0;f<1536;f++)for(int c=0;c<6;c++)in[f*6+c]=(float)(.5*Math.sin(2*Math.PI*12000*(source+f)/48000));
            int count=r.process(in,1536,out,0);source+=1536;
            if(block>0)for(int f=0;f<count;f++){energy+=out[f*6]*out[f*6];samples++;}
        }
        double gain=Math.sqrt(energy/samples/.125);
        // Linear interpolation averages about -1.76 dB at 12 kHz over the fractional phases.
        assertTrue("cubic 12 kHz gain "+gain,gain>.9&&gain<1.01);
    }
}
