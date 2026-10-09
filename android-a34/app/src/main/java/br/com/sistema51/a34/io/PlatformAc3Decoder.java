package br.com.sistema51.a34.io;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaFormat;
import org.json.JSONObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Experimental firmware decoder. Its gain and sample accounting are reported, never called transparent. */
public final class PlatformAc3Decoder {
    private PlatformAc3Decoder() {}
    private static final int[] BITRATES={32,40,48,56,64,80,96,112,128,160,192,224,256,320,384,448,512,576,640};
    private static final int[] COUNTS={2,1,2,3,3,4,4,5};
    private static final class Frame { int offset,size,channels; Frame(int o,int s,int c){offset=o;size=s;channels=c;} }
    public static JSONObject decode(File input, File output) throws Exception {
        if(input.length()>128L*1024*1024) throw new IOException("AC-3 maior que o limite de teste de 128 MiB.");
        byte[] data=new byte[(int)input.length()];try(DataInputStream in=new DataInputStream(new FileInputStream(input))){in.readFully(data);}
        List<Frame> frames=new ArrayList<>();int expectedChannels=0;
        for(int p=0;p<data.length;){
            if(data.length-p<8 || (data[p]&255)!=11 || (data[p+1]&255)!=119) throw new IOException("Use AC-3 elementar. Contêiner, DTS e IEC 61937 não são aceitos neste teste.");
            int fscod=(data[p+4]&255)>>>6, code=data[p+4]&63, bsid=(data[p+5]&255)>>>3;
            if(fscod!=0 || code>37 || bsid>8) throw new IOException("Este teste aceita somente AC-3 a 48 kHz.");
            int size=BITRATES[code/2]*4, acmod=(data[p+6]&255)>>>5, bit=51;
            if((acmod&1)!=0 && acmod!=1)bit+=2;if((acmod&4)!=0)bit+=2;if(acmod==2)bit+=2;
            int lfe=(((data[p+bit/8]&255)>>>(7-bit%8))&1),channels=COUNTS[acmod]+lfe;
            if(!((acmod==1&&lfe==0)||(acmod==2&&lfe==0)||(acmod==7&&lfe==1)))throw new IOException("Mapa AC-3 não suportado: use mono, estéreo FL/FR ou 5.1 completo.");
            if(!(channels==1||channels==2||channels==6)||p+size>data.length)throw new IOException("Quadro AC-3 ou mapa de canais não suportado.");
            if(expectedChannels!=0&&expectedChannels!=channels)throw new IOException("Mudança de canais no AC-3 requer nova sessão.");
            expectedChannels=channels;frames.add(new Frame(p,size,channels));p+=size;
        }
        if(frames.isEmpty())throw new IOException("AC-3 vazio.");
        MediaCodec codec=null;WavIO.Writer writer=null;boolean started=false;long begin=System.nanoTime(),lastProgress=begin;
        int queued=0,returned=0,outputChannels=0,encoding=0;long outputFrames=0;boolean inputEos=false,eos=false;
        String name="";String formatText="";float[] converted=new float[480*6];
        try {
            codec=MediaCodec.createDecoderByType("audio/ac3");name=codec.getName();
            MediaFormat format=MediaFormat.createAudioFormat("audio/ac3",48000,expectedChannels);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,65536);
            format.setInteger("max-output-channel-count",99);
            if(expectedChannels==6)format.setInteger(MediaFormat.KEY_CHANNEL_MASK,AudioFormat.CHANNEL_OUT_5POINT1);
            codec.configure(format,null,null,0);codec.start();started=true;
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            while(!eos){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Teste cancelado.");
                boolean progress=false;
                if(!inputEos){int index=codec.dequeueInputBuffer(0);if(index>=0){
                    ByteBuffer buffer=codec.getInputBuffer(index);if(buffer==null)throw new IOException("Buffer do codec indisponível.");buffer.clear();
                    if(queued<frames.size()){Frame f=frames.get(queued);if(buffer.remaining()<f.size)throw new IOException("Buffer AC-3 curto.");buffer.put(data,f.offset,f.size);codec.queueInputBuffer(index,0,f.size,queued*32000L,0);queued++;}
                    else {codec.queueInputBuffer(index,0,0,queued*32000L,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEos=true;}
                    progress=true;
                }}
                int index=codec.dequeueOutputBuffer(info,1000);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    MediaFormat actual=codec.getOutputFormat();formatText=actual.toString();int channels=actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    int rate=actual.getInteger(MediaFormat.KEY_SAMPLE_RATE);int pcm=actual.containsKey(MediaFormat.KEY_PCM_ENCODING)?actual.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;
                    if(channels!=expectedChannels||rate!=48000)throw new IOException("O codec alterou os canais/taxa: "+actual);
                    if(pcm!=AudioFormat.ENCODING_PCM_16BIT&&pcm!=AudioFormat.ENCODING_PCM_FLOAT)throw new IOException("PCM do codec não suportado: "+pcm);
                    if(writer!=null&&(channels!=outputChannels||pcm!=encoding))throw new IOException("Formato PCM mudou durante o teste.");
                    if(writer==null)writer=new WavIO.Writer(output,channels);outputChannels=channels;encoding=pcm;progress=true;
                }else if(index>=0){
                    try{
                        if(info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){
                            if(writer==null)throw new IOException("Codec devolveu PCM sem descrever o formato.");
                            ByteBuffer buffer=codec.getOutputBuffer(index);if(buffer==null)throw new IOException("PCM do codec indisponível.");
                            buffer.position(info.offset);buffer.limit(info.offset+info.size);buffer.order(ByteOrder.LITTLE_ENDIAN);
                            int sampleBytes=encoding==AudioFormat.ENCODING_PCM_FLOAT?4:2;
                            if(info.size%(outputChannels*sampleBytes)!=0)throw new IOException("PCM do codec desalinhado.");
                            while(buffer.hasRemaining()){int count=Math.min(480,buffer.remaining()/(outputChannels*sampleBytes));
                                for(int i=0;i<count*outputChannels;i++)converted[i]=encoding==AudioFormat.ENCODING_PCM_FLOAT?buffer.getFloat():buffer.getShort()/32768f;
                                writer.writeFrames(converted,count);outputFrames+=count;
                            }
                            returned++;
                        }
                        eos=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    }finally{codec.releaseOutputBuffer(index,false);}progress=true;
                }
                long now=System.nanoTime();if(progress)lastProgress=now;
                if(now-lastProgress>10000000000L||now-begin>120000000000L)throw new IOException("Tempo limite do decodificador.");
            }
            if(writer==null||outputFrames==0)throw new IOException("Codec não produziu PCM.");
            return new JSONObject().put("codec",name).put("mode","platform_experimental").put("inputAc3Frames",frames.size())
                    .put("inputSampleFrames",frames.size()*1536L).put("outputSampleFrames",outputFrames)
                    .put("sampleFrameDifference",outputFrames-frames.size()*1536L).put("outputBuffers",returned).put("eosReceived",eos)
                    .put("channels",outputChannels).put("sampleRate",48000).put("format",formatText)
                    .put("runtimeMs",(System.nanoTime()-begin)/1000000.0)
                    .put("fidelityValidated",false).put("note","Codec Samsung experimental: testes anteriores observaram ganho variável e diferença de duração. Sem garantia de decodificação transparente ou gapless.");
        }finally{
            try{if(writer!=null)writer.close();}finally{if(codec!=null){if(started)try{codec.stop();}catch(Exception ignored){}codec.release();}}
        }
    }
}
