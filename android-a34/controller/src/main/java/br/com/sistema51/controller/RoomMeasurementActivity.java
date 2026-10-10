package br.com.sistema51.controller;

import android.Manifest;
import android.app.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native A55 microphone + authenticated local transfer; no background recording. */
public final class RoomMeasurementActivity extends Activity {
    private static final String[] CHANNELS={"Frontal esquerda","Frontal direita","Central","Subwoofer","Surround esquerda","Surround direita"};
    private final ExecutorService work=Executors.newFixedThreadPool(2);
    private final AtomicBoolean busy=new AtomicBoolean();
    private volatile boolean cancelled,destroyed;
    private volatile RoomMicrophone microphone;
    private volatile String recordingId="";
    private String endpoint,token="",session="",expectedId;
    private volatile long revision;
    private String proposalId="";
    private TextView message,report;
    private EditText code;
    private Spinner channel,position;
    private CheckBox mapConfirmed;
    private Button measure,apply,disable,enable,reset,login;
    private int filters;
    private boolean correctionEnabled,proposalCurrent;
    private int storedFilters;
    private boolean available;
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(Color.rgb(234,242,245));t.setPadding(0,dp(8),0,dp(8));return t;}
    private Button button(String s,Runnable r){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setOnClickListener(v->r.run());return b;}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try{endpoint=LocalEndpoint.parse(getIntent().getStringExtra("endpoint"));}catch(Exception e){finish();return;}
        expectedId=getIntent().getStringExtra("identity");token=getIntent().getStringExtra("token");if(token==null)token="";
        ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(24),dp(40),dp(24),dp(32));root.setBackgroundColor(Color.rgb(11,16,25));scroll.addView(root);setContentView(scroll);
        root.addView(button("Voltar ao controle",this::finish));
        root.addView(text("Medir a sala pelo A55",26));
        root.addView(text("Mantenha uma fonte estável tocando no A34. Apoie o A55 na altura dos ouvidos, sem mudar sua orientação. Meça cada caixa em três posições: assento principal e pontos próximos à esquerda/direita.",15));
        root.addView(text("O sinal substitui temporariamente o som nas caixas durante a medição. O DSP continua executando. Os ajustes serão oferecidos para comparar e aplicar depois.",14));
        code=new EditText(this);code.setTextColor(Color.WHITE);code.setHintTextColor(Color.LTGRAY);code.setHint("Código de quatro dígitos do A34");code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);root.addView(code);
        login=button("Conectar para medir",()->{String candidate=code.getText().toString();runJob(()->{JSONObject unlocked=request("/api/unlock",new JSONObject().put("code",candidate),false);token=unlocked.getString("sessionToken");refresh();});});root.addView(login);
        channel=new Spinner(this);channel.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,CHANNELS));root.addView(channel);
        position=new Spinner(this);position.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"1 · Assento principal","2 · Próximo à esquerda","3 · Próximo à direita"}));root.addView(position);
        mapConfirmed=new CheckBox(this);mapConfirmed.setTextColor(Color.WHITE);mapConfirmed.setText("Conferi qual caixa corresponde a cada canal, inclusive central e sub");root.addView(mapConfirmed);
        message=text("Conectando…",15);root.addView(message);
        measure=button("Medir esta caixa",this::confirmMeasure);root.addView(measure);
        root.addView(button("Cancelar medição",this::cancelCapture));
        report=text("",14);root.addView(report);
        apply=button("Aplicar cortes sugeridos",()->new AlertDialog.Builder(this).setMessage("Aplicar os cortes exibidos? O microfone não é calibrado; compare por escuta com a correção desligada.")
                .setPositiveButton("Aplicar",(d,w)->runJob(()->show(request("/api/room/apply",new JSONObject().put("session",session).put("revision",revision).put("proposalId",proposalId).put("confirmApply",true),true))))
                .setNegativeButton("Voltar",null).show());root.addView(apply);
        disable=button("Desativar correção de sala",()->runJob(()->show(request("/api/room/disable",new JSONObject().put("session",session).put("revision",revision),true))));root.addView(disable);
        enable=button("Ativar correção salva",()->runJob(()->show(request("/api/room/enable",new JSONObject().put("session",session).put("revision",revision),true))));root.addView(enable);
        reset=button("Novo conjunto de medições",()->runJob(()->show(request("/api/room/reset",new JSONObject().put("session",session),true))));root.addView(reset);
        setBusy(false);runJob(()->{
            JSONObject info=request("/api/info",null,false);
            if(expectedId!=null&&!expectedId.equals(info.optString("id")))throw new IOException("O endereço agora pertence a outro DSP. Reconecte pelo controle.");
            if(info.optInt("roomCalibrationProtocol")!=1)throw new IOException("A atualização de correção de sala ainda precisa ser instalada no A34.");
            available=true;
            if(!token.isEmpty())refresh();else ui(()->message.setText("Informe o código do A34 para começar."));
        });
    }
    private void ui(Runnable action){runOnUiThread(()->{if(!destroyed&&!isFinishing())action.run();});}
    private interface Job{void run()throws Exception;}
    private void runJob(Job job){
        if(!busy.compareAndSet(false,true))return;cancelled=false;setBusy(true);
        work.execute(()->{
            try{job.run();}catch(Exception e){ui(()->message.setText(e.getMessage()==null?"Operação não concluída.":e.getMessage()));}
            finally{busy.set(false);ui(()->setBusy(false));}
        });
    }
    private void setBusy(boolean value){
        measure.setEnabled(!value&&available&&!token.isEmpty());apply.setEnabled(!value&&filters>0&&proposalCurrent);
        disable.setEnabled(!value&&!token.isEmpty()&&correctionEnabled);enable.setEnabled(!value&&!token.isEmpty()&&!correctionEnabled&&storedFilters>0);
        reset.setEnabled(!value&&available&&!token.isEmpty());login.setEnabled(!value&&available);
        code.setVisibility(token.isEmpty()?View.VISIBLE:View.GONE);login.setVisibility(token.isEmpty()?View.VISIBLE:View.GONE);
    }
    private void confirmMeasure(){
        if(!mapConfirmed.isChecked()){message.setText("Confira o mapa das caixas antes de medir.");return;}
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},55);return;
        }
        final int selectedChannel=channel.getSelectedItemPosition(),selectedPosition=position.getSelectedItemPosition();
        new AlertDialog.Builder(this).setMessage("Iniciar sinais baixos apenas em "+CHANNELS[selectedChannel]+"? Deixe o celular apoiado e evite falar durante o teste.")
                .setPositiveButton("Começar",(d,w)->runJob(()->capture(selectedChannel,selectedPosition))).setNegativeButton("Voltar",null).show();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==55){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)confirmMeasure();else message.setText("A medição precisa da permissão de microfone do A55.");}
    }
    private void refresh()throws Exception{
        JSONObject state=request("/api/state",null,true);session=state.getString("controlSession");revision=state.getLong("profileRevision");
        show(request("/api/room/status",null,true));
    }
    private void capture(int selectedChannel,int selectedPosition)throws Exception{
        refresh();
        checkCancelled();
        RoomMicrophone recorder=new RoomMicrophone(this);microphone=recorder;
        boolean began=false;String id="";
        try{
            checkCancelled();
            recorder.start();recorder.awaitReady();
            checkCancelled();
            JSONObject test=request("/api/room/begin",new JSONObject().put("session",session).put("revision",revision)
                    .put("channel",selectedChannel).put("position",selectedPosition).put("rawCapture",recorder.rawCapture())
                    .put("confirmPlayback",true).put("mapConfirmed",true),true);began=true;
            id=test.getString("id");recordingId=id;
            recorder.finishAfter(test.getInt("durationMs")+1500);
            ui(()->message.setText("Gravando "+CHANNELS[selectedChannel]+"… Mantenha o A55 imóvel."));
            byte[] pcm=recorder.awaitResult();checkCancelled();
            ui(()->message.setText("Enviando a gravação pela rede local…"));
            MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(pcm);
            StringBuilder sha=new StringBuilder();for(byte b:digest.digest())sha.append(String.format(Locale.ROOT,"%02x",b&255));
            for(int offset=0;offset<pcm.length;offset+=16384){
                checkCancelled();int size=Math.min(16384,pcm.length-offset);
                String data=Base64.encodeToString(pcm,offset,size,Base64.NO_WRAP);
                JSONObject ack=request("/api/room/chunk",new JSONObject().put("session",session).put("id",id).put("offset",offset).put("data",data),true);
                if(ack.getInt("receivedBytes")!=offset+size)throw new IOException("A gravação não chegou completa.");
            }
            checkCancelled();show(request("/api/room/finish",new JSONObject().put("session",session).put("id",id).put("bytes",pcm.length)
                    .put("rawCapture",recorder.rawCapture()).put("sha256",sha.toString()),true));
            began=false;
        }finally{
            recorder.close();microphone=null;
            if(began)try{request("/api/room/cancel",new JSONObject().put("session",session).put("id",id),true);}catch(Exception ignored){}
            recordingId="";
        }
    }
    private void checkCancelled(){if(cancelled||destroyed)throw new CancellationException("Medição cancelada.");}
    private void cancelCapture(){
        cancelled=true;RoomMicrophone recorder=microphone;if(recorder!=null)recorder.close();
        String id=recordingId;
        if(busy.get()&&!id.isEmpty())work.execute(()->{try{request("/api/room/cancel",new JSONObject().put("session",session).put("id",id),true);}catch(Exception ignored){}});
        if(message!=null)message.setText("Medição cancelada.");
    }
    private JSONObject request(String path,JSONObject body,boolean authenticated)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL(endpoint+path).openConnection();
        connection.setConnectTimeout(3000);connection.setReadTimeout(12000);connection.setInstanceFollowRedirects(false);
        if(authenticated)connection.setRequestProperty("Authorization","Bearer "+token);
        try{
            if(body!=null){byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(bytes.length);connection.setRequestProperty("Content-Type","application/json");try(OutputStream out=connection.getOutputStream()){out.write(bytes);}}
            int code=connection.getResponseCode();InputStream source=code>=400?connection.getErrorStream():connection.getInputStream();
            if(source==null)throw new IOException("A34 indisponível ("+code+").");
            JSONObject response;
            try(InputStream in=source;ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] block=new byte[4096];int n;while((n=in.read(block))!=-1){out.write(block,0,n);if(out.size()>262144)throw new IOException("Resposta grande demais.");}
                response=new JSONObject(out.toString("UTF-8"));
            }
            if(code==401&&authenticated){token="";throw new IOException("Sessão expirada. Informe o código do A34.");}
            if(code>=400)throw new IOException(response.optString("error","Operação recusada ("+code+")."));
            return response;
        }finally{connection.disconnect();}
    }
    private void show(JSONObject value)throws JSONException{
        revision=value.getLong("profileRevision");proposalId=value.optString("proposalId");filters=value.optInt("filterCount");
        proposalCurrent=value.optLong("proposalRevision",-1)==revision;correctionEnabled=value.optBoolean("enabled");storedFilters=value.optInt("storedFilterCount");
        JSONArray measurements=value.getJSONArray("measurements"),channels=value.getJSONObject("proposal").getJSONArray("filters");
        StringBuilder summary=new StringBuilder();
        int[] counts=new int[6],rawCounts=new int[6];
        for(int i=0;i<measurements.length();i++){JSONObject m=measurements.getJSONObject(i);counts[m.getInt("channel")]++;if(m.optBoolean("rawCapture"))rawCounts[m.getInt("channel")]++;}
        for(int c=0;c<6;c++){
            summary.append(CHANNELS[c]).append(": ").append(counts[c]).append("/3 posições · ").append(rawCounts[c]).append(" sem processamento\n");
            JSONArray selected=channels.getJSONArray(c);
            for(int b=0;b<selected.length();b++){JSONObject f=selected.getJSONObject(b);summary.append(String.format(Locale.ROOT,"  %.0f Hz · %.1f dB · Q %.1f\n",f.getDouble("frequencyHz"),f.getDouble("gainDb"),f.getDouble("q")));}
        }
        summary.append("\n").append(value.optString("notice")).append("\nAtrasos preservados.");
        final String text=summary.toString();
        ui(()->{report.setText(text);message.setText(value.optBoolean("enabled")?"Correção de sala ativa.":filters>0?"Sugestão pronta para comparar.":"Meça três posições por caixa para calcular cortes.");});
    }
    @Override protected void onStop(){if(busy.get())cancelCapture();super.onStop();}
    @Override protected void onDestroy(){destroyed=true;RoomMicrophone recorder=microphone;if(recorder!=null)recorder.close();work.shutdown();super.onDestroy();}
}
