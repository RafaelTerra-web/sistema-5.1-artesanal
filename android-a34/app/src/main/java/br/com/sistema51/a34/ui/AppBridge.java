package br.com.sistema51.a34.ui;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Thin boundary between the activity and audio/storage implementation. */
public abstract class AppBridge {
    public interface Factory {
        AppBridge create(Context context);
    }

    public interface Callback {
        void onComplete(String report);
        void onError(String message);
    }

    private static Factory factory;

    public static synchronized void installFactory(Factory value) {
        factory = value;
    }

    public static synchronized AppBridge create(Context context) {
        return factory == null ? new LocalBridge(context) : factory.create(context);
    }

    public abstract JSONObject loadProfile();
    public abstract void applyProfile(JSONObject profile);
    public void saveProfile(JSONObject profile) { applyProfile(profile); }
    public abstract JSONObject snapshot();
    public abstract void start();
    public abstract void stop();
    public abstract void requestUsbPermission();
    public abstract void selfTest(Callback callback);
    public void hardwareTest(Callback callback) { callback.onError("Diagnóstico de hardware indisponível nesta versão."); }
    public abstract void testFile(Uri uri, Callback callback);
    public abstract String lastReport();
    public Uri lastOutputUri() { return null; }
    public abstract void close();

    /** The documented PC reference; arrays always follow FL, FR, C, LFE, SL, SR. */
    public static JSONObject defaultProfile() {
        JSONObject p = new JSONObject();
        try {
            p.put("schemaVersion", 1);
            p.put("name", "Referência PC");
            p.put("sampleRate", 48000);
            p.put("masterGain", 0.04);
            p.put("muted", false);
            p.put("bypass", false);
            p.put("inputMode", "NATIVE_5_1");
            p.put("channelTrim", new JSONArray(new double[] {1, 1, 1, 1, 1, 1}));
            p.put("delaySamples", new JSONArray(new int[] {3686, 3686, 278, 0, 3408, 3408}));
            p.put("lfeEqEnabled", true);
            p.put("lfeEqFrequenciesHz", new JSONArray(new int[] {20, 25, 30, 40, 50, 60, 80, 100, 120}));
            p.put("lfeEqGainDb", new JSONArray(new double[] {6, 6, 6, 5.5, 1.5, -4, 1, 1, -2.5}));
            p.put("lfeEqQ", new JSONArray(new double[] {2, 2, 2, 2, 2, 2, 2, 2, 2}));
            p.put("lfeHeadroom", 1.0 / 3.0);
            p.put("automaticLfeHeadroom", true);
            p.put("surroundCrossoverEnabled", true);
            p.put("surroundCutoffHz", 90);
            p.put("surroundBassSend", 1);
            p.put("centerBassCopyEnabled", false);
            p.put("centerBassCutoffHz", 120);
            p.put("centerBassSend", 1);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return p;
    }

    // Safe editor fallback: never implies an audio engine or USB test exists.
    private static final class LocalBridge extends AppBridge {
        private final Context context;
        private JSONObject profile;

        LocalBridge(Context context) {
            this.context = context.getApplicationContext();
            String saved = this.context.getSharedPreferences("a34-ui-fallback", Context.MODE_PRIVATE)
                    .getString("profile", null);
            try { profile = saved == null ? defaultProfile() : new JSONObject(saved); }
            catch (JSONException ignored) { profile = defaultProfile(); }
        }

        @Override public JSONObject loadProfile() { return profile; }
        @Override public void applyProfile(JSONObject value) { profile = value; }
        @Override public void saveProfile(JSONObject value) {
            applyProfile(value);
            context.getSharedPreferences("a34-ui-fallback", Context.MODE_PRIVATE)
                    .edit().putString("profile", value.toString()).apply();
        }
        @Override public JSONObject snapshot() {
            JSONObject s = new JSONObject();
            try {
                s.put("state", "UNAVAILABLE");
                s.put("title", "Motor de áudio indisponível");
                s.put("detail", "O editor de perfis está disponível. A implementação de áudio não foi conectada a esta versão.");
                s.put("usbSummary", "USB não consultado");
                s.put("inputSummary", "Sem captura ativa");
                s.put("outputSummary", "Sem reprodução ativa");
                s.put("running", false);
                s.put("requiresRecordingPermission", false);
            } catch (JSONException ignored) { }
            return s;
        }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void requestUsbPermission() { }
        @Override public void selfTest(Callback callback) { callback.onError("Motor de testes indisponível nesta versão."); }
        @Override public void testFile(Uri uri, Callback callback) { callback.onError("Processamento de arquivos indisponível nesta versão."); }
        @Override public String lastReport() { return "Nenhum teste executado."; }
        @Override public void close() { }
    }
}
