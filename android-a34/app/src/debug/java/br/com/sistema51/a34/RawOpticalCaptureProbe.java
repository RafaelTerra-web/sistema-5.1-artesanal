package br.com.sistema51.a34;

import android.content.Context;
import android.hardware.usb.*;
import android.os.ParcelFileDescriptor;
import br.com.sistema51.a34.usb.RawUsbCapture;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Debug-only native capture. Optional duplex output contains fixed zeros only. */
public final class RawOpticalCaptureProbe {
    private RawOpticalCaptureProbe(){}
    private static void require(boolean condition,String text)throws IOException{if(!condition)throw new IOException(text);}
    private static void durable(File file,JSONObject value)throws Exception{
        try(FileOutputStream out=new FileOutputStream(file)){out.write(value.toString(2).getBytes(StandardCharsets.UTF_8));out.flush();out.getFD().sync();}
    }
    public static JSONObject run(Context context,int seconds)throws Exception{
        return run(context,seconds,false);
    }
    public static JSONObject run(Context context,int seconds,boolean silentDuplex)throws Exception{
        return run(context,seconds,silentDuplex,false);
    }
    private static org.json.JSONArray usbOutputs(Context context)throws Exception{
        android.media.AudioManager audio=(android.media.AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        org.json.JSONArray result=new org.json.JSONArray();
        for(android.media.AudioDeviceInfo d:audio.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS))
            if(d.getType()==android.media.AudioDeviceInfo.TYPE_USB_DEVICE||d.getType()==android.media.AudioDeviceInfo.TYPE_USB_HEADSET)
                result.put(new JSONObject().put("id",d.getId()).put("address",d.getAddress()).put("name",d.getProductName())
                    .put("channels",new org.json.JSONArray(d.getChannelCounts())));
        return result;
    }
    public static JSONObject run(Context context,int seconds,boolean silentDuplex,boolean preserveAndroidOutput)throws Exception{
        return run(context,seconds,silentDuplex,preserveAndroidOutput,6);
    }
    public static JSONObject run(Context context,int seconds,boolean silentDuplex,boolean preserveAndroidOutput,int outputChannels)throws Exception{
        return run(context,seconds,silentDuplex,preserveAndroidOutput,outputChannels,0);
    }
    public static JSONObject run(Context context,int seconds,boolean silentDuplex,boolean preserveAndroidOutput,int outputChannels,int handoffMillis)throws Exception{
        require(handoffMillis>=0&&handoffMillis<=3000,"Espera fora da faixa de teste.");
        require(outputChannels==6||outputChannels==8,"Saída de teste deve ter seis ou oito slots.");
        require(!preserveAndroidOutput||!silentDuplex,"Hybrid probe captures only; it cannot submit OUT audio.");
        require(seconds>=1&&seconds<=10,"Duração fora da faixa");
        UsbManager manager=(UsbManager)context.getSystemService(Context.USB_SERVICE);
        UsbDevice device=null;
        for(UsbDevice candidate:manager.getDeviceList().values())if(candidate.getVendorId()==0x0d8c&&candidate.getProductId()==0x0102){require(device==null,"Mais de uma CM6206");device=candidate;}
        require(device!=null&&manager.hasPermission(device),"CM6206 ausente ou sem permissão");
        UsbInterface control=null,stream=null,output=null;UsbEndpoint endpoint=null,outputEndpoint=null;
        for(int i=0;i<device.getInterfaceCount();i++){
            UsbInterface candidate=device.getInterface(i);
            if(candidate.getId()==0&&candidate.getAlternateSetting()==0&&candidate.getInterfaceClass()==1&&candidate.getInterfaceSubclass()==1)control=candidate;
            if(candidate.getId()==2&&candidate.getAlternateSetting()==1&&candidate.getInterfaceClass()==1&&candidate.getInterfaceSubclass()==2){
                for(int j=0;j<candidate.getEndpointCount();j++){
                    UsbEndpoint ep=candidate.getEndpoint(j);
                    if(ep.getDirection()==UsbConstants.USB_DIR_IN&&ep.getType()==UsbConstants.USB_ENDPOINT_XFER_ISOC&&ep.getMaxPacketSize()==200&&ep.getInterval()==1){stream=candidate;endpoint=ep;}
                }
            }
            if(candidate.getId()==1&&candidate.getAlternateSetting()==(outputChannels==8?1:4)&&candidate.getInterfaceClass()==1&&candidate.getInterfaceSubclass()==2){
                for(int j=0;j<candidate.getEndpointCount();j++){UsbEndpoint ep=candidate.getEndpoint(j);
                    if(ep.getDirection()==UsbConstants.USB_DIR_OUT&&ep.getType()==UsbConstants.USB_ENDPOINT_XFER_ISOC&&ep.getMaxPacketSize()==(outputChannels==8?784:584)&&ep.getInterval()==1){output=candidate;outputEndpoint=ep;}}
            }
        }
        require(control!=null&&stream!=null,"Topologia de captura diferente da unidade validada");
        require(!silentDuplex||output!=null,"Saída USB6 não identificada");
        File dir=new File(context.getFilesDir(),"hardware");require(dir.isDirectory()||dir.mkdirs(),"Diretório privado indisponível");
        File journal=new File(dir,"raw-capture-journal.json"),pcm=new File(dir,"raw-optical-"+System.currentTimeMillis()+".pcm");
        JSONObject report=new JSONObject().put("kind","native_usb_optical_capture_probe").put("playback",false).put("silentDuplex",silentDuplex).put("seconds",seconds)
            .put("pcmFile",pcm.getName()).put("endpoint",endpoint.getAddress()).put("packetBytes",200).put("usbAudioDriverTemporarilyDetached",true)
            .put("opticalSourceValidated",false).put("ac3Validated",false).put("bitPerfectValidated",false);
        report.put("preserveAndroidOutput",preserveAndroidOutput).put("usbOutputsBefore",usbOutputs(context));
        report.put("outputSlots",outputChannels);
        report.put("handoffMillis",handoffMillis);
        UsbDeviceConnection connection=manager.openDevice(device);require(connection!=null,"USB open falhou");
        boolean controlClaimed=false,streamClaimed=false,outputClaimed=false,sourceTouched=false;int original=-1;
        try{
            byte[] raw=connection.getRawDescriptors();boolean selector=false;
            for(int i=0;i<raw.length;){int n=raw[i]&255;require(n>=2&&i+n<=raw.length,"Descritor inválido");
                if(n>=10&&(raw[i+1]&255)==0x24&&(raw[i+2]&255)==5&&(raw[i+3]&255)==7&&(raw[i+4]&255)==4
                    &&raw[i+5]==8&&raw[i+6]==15&&raw[i+7]==16&&raw[i+8]==2)selector=true;i+=n;}
            require(selector,"Seletor 7 / pin 3 SPDIF não confirmado nos descritores");
            if(!preserveAndroidOutput){
            controlClaimed=connection.claimInterface(control,true);require(controlClaimed,"Não foi possível acessar controle UAC");
            if(handoffMillis>0)android.os.SystemClock.sleep(handoffMillis);
            streamClaimed=connection.claimInterface(stream,true);require(streamClaimed,"Não foi possível acessar captura UAC");
            byte[] pin=new byte[1];int count=connection.controlTransfer(0xa1,0x81,0,7<<8,pin,1,500);
            report.put("selectorReadBytes",count);require(count==1,"GET_CUR do seletor falhou mesmo com interface exclusiva");
            original=pin[0]&255;require(original>=1&&original<=4,"Seletor original inválido");
            report.put("originalPin",original);
            JSONObject saved=new JSONObject().put("originalPin",original).put("targetPin",3).put("restored",false);
            if(journal.exists()){
                try(InputStream in=new FileInputStream(journal);ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
                    require(new JSONObject(out.toString("UTF-8")).optBoolean("restored"),"Journal anterior exige recuperação antes de novo teste");
                }
            }
            durable(journal,saved);
            if(original!=3){pin[0]=3;sourceTouched=true;require(connection.controlTransfer(0x21,1,0,7<<8,pin,1,500)==1,"SET_CUR SPDIF falhou");}
            pin[0]=0;require(connection.controlTransfer(0xa1,0x81,0,7<<8,pin,1,500)==1&&(pin[0]&255)==3,"Seleção SPDIF não confirmada");
            }else{streamClaimed=connection.claimInterface(stream,true);require(streamClaimed,"Captura UAC ocupada.");}
            require(connection.setInterface(stream),"Alternate de captura falhou");
            byte[] frequency={(byte)0x80,(byte)0xbb,0};
            int frequencySet=connection.controlTransfer(0x22,1,0x0100,endpoint.getAddress(),frequency,3,500);
            report.put("setFrequencyBytes",frequencySet);require(frequencySet==3,"Taxa da captura não configurada");
            report.put("usbOutputsDuring",usbOutputs(context));
            if(silentDuplex){outputClaimed=connection.claimInterface(output,true);require(outputClaimed,"Claim da saída falhou");
                require(connection.setInterface(output),"Alternate da saída falhou");
                int outputFrequencySet=connection.controlTransfer(0x22,1,0x0100,outputEndpoint.getAddress(),frequency,3,500);
                report.put("setOutputFrequencyBytes",outputFrequencySet);require(outputFrequencySet==3,"Taxa da saída não configurada");}
            try(ParcelFileDescriptor file=ParcelFileDescriptor.open(pcm,ParcelFileDescriptor.MODE_CREATE|ParcelFileDescriptor.MODE_TRUNCATE|ParcelFileDescriptor.MODE_WRITE_ONLY)){
                long[] stats=RawUsbCapture.capture(connection.getFileDescriptor(),endpoint.getAddress(),200,seconds,file.getFd(),silentDuplex?outputEndpoint.getAddress():0,outputChannels*96);
                report.put("bytes",stats[0]).put("packets",stats[1]).put("badPackets",stats[2]).put("shortPackets",stats[3]).put("urbs",stats[4]).put("errno",stats[5]);
                report.put("silentOutputPackets",stats[6]).put("silentOutputPacketErrors",stats[7]).put("silentOutputBytes",stats[8]);
                report.put("ok",stats[0]>0&&stats[2]==0&&stats[5]==0&&stats[7]==0&&(!silentDuplex||stats[6]>0));
                report.put("usbOutputsAfter",usbOutputs(context));
            }
        }catch(Exception error){report.put("ok",false).put("error",error.toString());}
        finally{
            boolean restored=!sourceTouched;
            if(sourceTouched)try{byte[] pin={(byte)original};require(connection.controlTransfer(0x21,1,0,7<<8,pin,1,500)==1,"Restore SET_CUR falhou");
                pin[0]=0;require(connection.controlTransfer(0xa1,0x81,0,7<<8,pin,1,500)==1&&(pin[0]&255)==original,"Restore GET_CUR falhou");restored=true;
            }catch(Exception error){report.put("restorationError",error.toString()).put("ok",false);}
            report.put("sourceRestored",restored);
            if(original>=1&&journal.exists())durable(journal,new JSONObject().put("originalPin",original).put("targetPin",3).put("restored",restored));
            try{
                if(outputClaimed)report.put("outputReleased",connection.releaseInterface(output));
                if(streamClaimed)report.put("streamReleased",connection.releaseInterface(stream));
                if(controlClaimed)report.put("controlReleased",connection.releaseInterface(control));
                report.put("reconnectAudioControlResult",RawUsbCapture.reconnect(connection.getFileDescriptor(),0));
            }finally{connection.close();}
        }
        durable(new File(dir,"raw-optical-report.json"),report);return report;
    }
}
