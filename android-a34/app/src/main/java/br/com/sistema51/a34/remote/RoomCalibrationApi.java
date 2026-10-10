package br.com.sistema51.a34.remote;

import br.com.sistema51.a34.AppFacade;
import br.com.sistema51.a34.ProfileStore;
import br.com.sistema51.a34.dsp.RoomCorrection;
import br.com.sistema51.a34.room.*;
import org.json.*;
import java.util.*;

/** Authenticated LAN handler; uploads/analysis never run on the audio callback. */
final class RoomCalibrationApi implements AutoCloseable {
    interface Host {
        long profileRevision();
        JSONObject loadProfile();
        RoomPlayback.Ticket beginRoomTest(String id,int channel,long revision);
        void cancelRoomTest();
        void setRoomCorrection(RoomCorrection correction,long revision);
    }
    private final Host facade;
    private final Map<String,RoomMeasurementAnalyzer.Measurement> measurements=new LinkedHashMap<>();
    private Upload upload;
    private long baselineRevision=-1,proposalRevision=-1;
    private RoomCorrection proposal=RoomCorrection.empty();
    private String proposalId="";
    private static final class Upload {
        final String id;final int position;final boolean raw;final long revision,expires;
        final RoomPlayback.Ticket ticket;
        final RoomRecordingBuffer recording=new RoomRecordingBuffer();
        Upload(String id,int position,boolean raw,long revision,RoomPlayback.Ticket ticket){
            this.id=id;this.position=position;this.raw=raw;this.revision=revision;this.ticket=ticket;
            expires=System.nanoTime()+120_000_000_000L;
        }
    }
    RoomCalibrationApi(Host facade){this.facade=facade;}
    RoomCalibrationApi(AppFacade facade){this(new Host(){
        public long profileRevision(){return facade.profileRevision();}
        public JSONObject loadProfile(){return facade.loadProfile();}
        public RoomPlayback.Ticket beginRoomTest(String id,int channel,long revision){return facade.beginRoomTest(id,channel,revision);}
        public void cancelRoomTest(){facade.cancelRoomTest();}
        public void setRoomCorrection(RoomCorrection correction,long revision){facade.setRoomCorrection(correction,revision);}
    });}
    synchronized JSONObject handle(String operation,JSONObject body)throws Exception{
        if(upload!=null&&System.nanoTime()>upload.expires){facade.cancelRoomTest();upload=null;}
        if("status".equals(operation))return snapshot();
        if("reset".equals(operation)){facade.cancelRoomTest();upload=null;measurements.clear();baselineRevision=-1;proposalId="";proposal=RoomCorrection.empty();return snapshot();}
        if("cancel".equals(operation)){
            if(upload!=null&&!upload.id.equals(body.optString("id")))throw new IllegalStateException("Esta gravação não está ativa.");
            facade.cancelRoomTest();upload=null;return snapshot();
        }
        if("begin".equals(operation)){
            if(upload!=null)throw new IllegalStateException("Envie ou cancele a gravação anterior.");
            if(!Boolean.TRUE.equals(body.opt("confirmPlayback"))||!Boolean.TRUE.equals(body.opt("mapConfirmed")))
                throw new IllegalArgumentException("Confirme o teste audível e o mapa das seis caixas.");
            int channel=integer(body,"channel",0,5),position=integer(body,"position",0,4);
            long revision=body.getLong("revision");
            if(baselineRevision>=0&&baselineRevision!=revision)throw new IllegalStateException("O perfil mudou entre medições. Inicie um novo conjunto.");
            boolean raw=Boolean.TRUE.equals(body.opt("rawCapture"));
            String id=UUID.randomUUID().toString();
            RoomPlayback.Ticket ticket=facade.beginRoomTest(id,channel,revision);
            upload=new Upload(id,position,raw,revision,ticket);baselineRevision=revision;
            return new JSONObject().put("id",id).put("gain",ticket.signal.gain)
                    .put("durationMs",Math.ceil(ticket.signal.frames()/48.0)).put("sampleRate",48000);
        }
        if("apply".equals(operation)){
            if(upload!=null)throw new IllegalStateException("Termine a medição antes de aplicar.");
            if(!Boolean.TRUE.equals(body.opt("confirmApply"))||!proposalId.equals(body.optString("proposalId"))||proposalId.isEmpty())
                throw new IllegalArgumentException("Confira e confirme a sugestão de correção.");
            long revision=body.getLong("revision");
            int count=0;for(int c=0;c<6;c++)count+=proposal.count(c);
            if(count==0)throw new IllegalArgumentException("Nenhum corte sugerido para aplicar.");
            if(revision!=proposalRevision)throw new java.util.ConcurrentModificationException("O perfil mudou após a análise. Faça novas medições.");
            facade.setRoomCorrection(proposal.withEnabled(true),revision);
            return snapshot();
        }
        if("disable".equals(operation)||"enable".equals(operation)){
            if(upload!=null)throw new IllegalStateException("Termine a medição antes de desativar.");
            RoomCorrection current=ProfileStore.fromJson(facade.loadProfile()).getRoomCorrection();
            int count=0;for(int c=0;c<6;c++)count+=current.count(c);
            if("enable".equals(operation)&&count==0)throw new IllegalStateException("Nenhuma correção salva.");
            facade.setRoomCorrection(current.withEnabled("enable".equals(operation)),body.getLong("revision"));
            return snapshot();
        }
        Upload current=upload;
        if(current==null||!current.id.equals(body.optString("id")))throw new IllegalStateException("Gravação expirada ou desconhecida.");
        if("chunk".equals(operation)){
            int offset=integer(body,"offset",0,RoomTestSignal.MAX_RECORD_SECONDS*48000*2);
            String data=body.getString("data");
            if(data.length()>22000)throw new IllegalArgumentException("Bloco de gravação grande demais.");
            byte[] bytes=Base64.getDecoder().decode(data);
            current.recording.append(offset,bytes);
            return new JSONObject().put("receivedBytes",current.recording.size());
        }
        if("finish".equals(operation)){
            boolean complete=false;
            try{
                if(facade.profileRevision()!=current.revision)throw new java.util.ConcurrentModificationException("O perfil mudou durante a medição; repita.");
                if(!"done".equals(current.ticket.state()))throw new IllegalStateException("O sinal de teste não terminou ou foi interrompido.");
                current.recording.verify(integer(body,"bytes",0,RoomTestSignal.MAX_RECORD_SECONDS*48000*2),body.optString("sha256"));
                RoomMeasurementAnalyzer.Measurement measured=RoomMeasurementAnalyzer.analyze(current.recording.bytes(),current.recording.size(),
                        current.ticket.signal,current.position,current.raw&&Boolean.TRUE.equals(body.opt("rawCapture")));
                measurements.put(measured.channel+":"+measured.position,measured);
                proposal=RoomCorrectionPlanner.suggest(measurements.values());proposalRevision=current.revision;
                proposalId=UUID.randomUUID().toString();
                complete=true;
            }finally{if(!complete)facade.cancelRoomTest();upload=null;}
            return snapshot();
        }
        throw new IllegalArgumentException("Operação de sala desconhecida.");
    }
    private static int integer(JSONObject json,String key,int min,int max)throws JSONException{
        Object value=json.get(key);
        if(!(value instanceof Number))throw new IllegalArgumentException(key+" deve ser inteiro.");
        double number=((Number)value).doubleValue();
        if(!Double.isFinite(number)||number!=Math.rint(number)||number<min||number>max)throw new IllegalArgumentException(key+" fora da faixa.");
        return (int)number;
    }
    private JSONObject snapshot()throws JSONException{
        JSONArray collected=new JSONArray();int filters=0;
        for(RoomMeasurementAnalyzer.Measurement m:measurements.values()){
            JSONArray levels=new JSONArray();
            for(double db:m.db)levels.put(Double.isFinite(db)?db:JSONObject.NULL);
            collected.put(new JSONObject().put("channel",m.channel).put("position",m.position).put("rawCapture",m.rawCapture)
                    .put("responseDb",levels).put("noiseDb",m.noiseDb).put("markerCorrelation",m.markerCorrelation));
        }
        for(int c=0;c<6;c++)filters+=proposal.count(c);
        RoomCorrection current=ProfileStore.fromJson(facade.loadProfile()).getRoomCorrection();
        int stored=0;for(int c=0;c<6;c++)stored+=current.count(c);
        return new JSONObject().put("measurements",collected).put("uploadActive",upload!=null)
                .put("proposalId",proposalId).put("proposal",ProfileStore.roomToJson(proposal)).put("filterCount",filters)
                .put("profileRevision",facade.profileRevision()).put("proposalRevision",proposalRevision)
                .put("storedFilterCount",stored).put("enabled",current.isEnabled())
                .put("delayChanges",false).put("microphoneCalibrated",false)
                .put("notice","Sugestão assistida: três posições por caixa e captura sem processamento. O microfone do celular não é calibrado; confira por escuta antes de aplicar.");
    }
    @Override public synchronized void close(){facade.cancelRoomTest();upload=null;measurements.clear();}
}
