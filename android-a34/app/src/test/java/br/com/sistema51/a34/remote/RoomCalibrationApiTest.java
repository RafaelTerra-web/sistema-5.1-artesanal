package br.com.sistema51.a34.remote;

import br.com.sistema51.a34.ProfileStore;
import br.com.sistema51.a34.dsp.*;
import br.com.sistema51.a34.room.*;
import org.json.*;
import org.junit.Test;
import java.util.*;
import java.security.*;
import static org.junit.Assert.*;

public final class RoomCalibrationApiTest {
    private static final class Host implements RoomCalibrationApi.Host {
        AudioProfile profile=AudioProfile.defaultProfile().toBuilder().masterGain(.2f).build();
        long revision=7;int starts,cancels,applies;
        final RoomPlayback playback=new RoomPlayback();
        public long profileRevision(){return revision;}
        public JSONObject loadProfile(){return ProfileStore.toJson(profile,"Perfil preservado");}
        public RoomPlayback.Ticket beginRoomTest(String id,int c,long r){if(r!=revision)throw new ConcurrentModificationException();starts++;return playback.begin(id,c,profile);}
        public void cancelRoomTest(){cancels++;playback.cancel();}
        public void setRoomCorrection(RoomCorrection correction,long r){if(r!=revision)throw new ConcurrentModificationException();profile=profile.toBuilder().roomCorrection(correction).build();revision++;applies++;}
    }
    private JSONObject begin(int channel,int position,long revision)throws Exception{
        return new JSONObject().put("channel",channel).put("position",position).put("revision",revision)
                .put("confirmPlayback",true).put("mapConfirmed",true).put("rawCapture",true);
    }
    private String sha(byte[] bytes)throws Exception{StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    private JSONObject measure(RoomCalibrationApi api,Host host,int position,boolean rawAtFinish)throws Exception{
        JSONObject ack=api.handle("begin",begin(0,position,host.revision));String id=ack.getString("id");
        RoomTestSignal signal=host.playback.ticket().signal;
        int n=signal.frames()+60000;float[] frame=new float[480*6];
        // Complete the existing callback, without opening or stopping any hardware.
        while(!"done".equals(host.playback.ticket().state())){Arrays.fill(frame,0);host.playback.render(frame,480,host.profile);}
        byte[] pcm=new byte[n*2];
        for(int f=0;f<signal.frames();f++){
            // Simulate a persistent narrow resonance at 89.8 Hz.
            double db=0,t=f/48000.0;
            for(int band=0;band<signal.toneCount();band++)if(t>=signal.toneStart(band)&&t<signal.toneStart(band)+RoomTestSignal.TONE&&band==7)db=8;
            int value=(int)Math.round(signal.sample(f)*.1*Math.pow(10,db/20)*32767);
            pcm[(f+24000)*2]=(byte)value;pcm[(f+24000)*2+1]=(byte)(value>>>8);
        }
        for(int offset=0;offset<pcm.length;offset+=16384){
            byte[] chunk=Arrays.copyOfRange(pcm,offset,Math.min(pcm.length,offset+16384));
            api.handle("chunk",new JSONObject().put("id",id).put("offset",offset).put("data",Base64.getEncoder().encodeToString(chunk)));
        }
        return api.handle("finish",new JSONObject().put("id",id).put("bytes",pcm.length).put("sha256",sha(pcm)).put("rawCapture",rawAtFinish));
    }
    @Test public void startsOnlyAfterExplicitConfirmationAndGuardsWrongCancellation()throws Exception{
        Host host=new Host();RoomCalibrationApi api=new RoomCalibrationApi(host);JSONObject b=begin(0,0,7);b.put("confirmPlayback",false);
        try{api.handle("begin",b);fail();}catch(IllegalArgumentException expected){}assertEquals(0,host.starts);
        JSONObject ack=api.handle("begin",begin(0,0,7));
        try{api.handle("cancel",new JSONObject().put("id","old-recording"));fail();}catch(IllegalStateException expected){}
        assertEquals(0,host.cancels);assertTrue(api.handle("status",new JSONObject()).getBoolean("uploadActive"));
        api.handle("cancel",new JSONObject().put("id",ack.getString("id")));assertEquals(1,host.cancels);
    }
    @Test public void captureUploadPreviewApplyAndABPreserveAllOtherSettings()throws Exception{
        Host host=new Host();RoomCalibrationApi api=new RoomCalibrationApi(host);JSONObject before=host.loadProfile();
        JSONObject result=null;for(int p=0;p<3;p++)result=measure(api,host,p,true);
        assertEquals(1,result.getInt("filterCount"));assertFalse(host.profile.getRoomCorrection().isEnabled());assertEquals(0,host.applies);
        api.handle("apply",new JSONObject().put("proposalId",result.getString("proposalId")).put("revision",7).put("confirmApply",true));
        assertTrue(host.profile.getRoomCorrection().isEnabled());
        JSONObject after=host.loadProfile();before.remove("roomCorrection");after.remove("roomCorrection");assertEquals(before.toString(),after.toString());
        api.handle("disable",new JSONObject().put("revision",8));assertFalse(host.profile.getRoomCorrection().isEnabled());
        api.handle("enable",new JSONObject().put("revision",9));assertTrue(host.profile.getRoomCorrection().isEnabled());
        assertEquals(1,host.profile.getRoomCorrection().count(0));assertEquals(3686,host.profile.getDelaySamples(0));
    }
    @Test public void processedCaptureCannotProduceFiltersAndIncompleteTestCannotFinish()throws Exception{
        Host host=new Host();RoomCalibrationApi api=new RoomCalibrationApi(host);
        JSONObject result=null;for(int p=0;p<3;p++)result=measure(api,host,p,false);
        assertEquals(0,result.getInt("filterCount"));
        api.handle("reset",new JSONObject());
        JSONObject ack=api.handle("begin",begin(0,0,7));
        try{api.handle("finish",new JSONObject().put("id",ack.getString("id")));fail();}catch(IllegalStateException expected){}
        assertFalse(api.handle("status",new JSONObject()).getBoolean("uploadActive"));
        assertNull(host.playback.ticket());
    }
    @Test public void profileRevisionChangeInvalidatesMeasurementSet()throws Exception{
        Host host=new Host();RoomCalibrationApi api=new RoomCalibrationApi(host);measure(api,host,0,true);host.revision++;
        try{api.handle("begin",begin(0,1,8));fail();}catch(IllegalStateException expected){}
        assertEquals(0,host.applies);
    }
    @Test public void profileRoundTripKeepsCorrectionAndLegacyProfilesRemainDisabled()throws Exception{
        Host host=new Host();JSONObject legacy=host.loadProfile();legacy.remove("roomCorrection");assertFalse(ProfileStore.fromJson(legacy).getRoomCorrection().isEnabled());
        RoomCorrection.Filter[][] filters={{},{},{},{new RoomCorrection.Filter(80,-3,2)},{},{}};
        AudioProfile p=host.profile.toBuilder().roomCorrection(new RoomCorrection(true,filters)).build();
        AudioProfile restored=ProfileStore.fromJson(ProfileStore.toJson(p,"Medição"));
        assertEquals(p.getRoomCorrection(),restored.getRoomCorrection());assertEquals(.2,restored.getMasterGain(),1e-7);
        assertEquals(278,restored.getDelaySamples(3));
    }
}
