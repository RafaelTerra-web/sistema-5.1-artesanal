package br.com.sistema51.a34;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import br.com.sistema51.a34.dsp.AudioProfile;

/** One validated profile; Android preferences persist atomically between APK updates. */
public final class ProfileStore {
    private static final String PREFS = "audio_profiles_v1", KEY = "active";
    private ProfileStore() {}
    public static AudioProfile load(Context context) {
        String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null);
        if (saved == null) return AudioProfile.defaultProfile();
        try { return fromJson(new JSONObject(saved)); }
        catch (Exception e) { throw new IllegalStateException("Perfil salvo inválido. Importe um perfil válido ou restaure o padrão.", e); }
    }
    public static JSONObject loadJson(Context context) {
        try {
            String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null);
            return saved == null ? toJson(AudioProfile.defaultProfile(), "Referência PC") : toJson(fromJson(new JSONObject(saved)), new JSONObject(saved).optString("name", "Meu perfil"));
        } catch (Exception e) { throw new IllegalStateException("Não foi possível ler o perfil.", e); }
    }
    public static void save(Context context, AudioProfile profile) { saveJson(context, toJson(profile, "Meu perfil")); }
    public static void saveJson(Context context, JSONObject json) {
        AudioProfile profile = fromJson(json);
        JSONObject normalized = toJson(profile, checkedName(json.optString("name", "Meu perfil")));
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, normalized.toString()).commit()) {
            throw new IllegalStateException("Não foi possível salvar o perfil.");
        }
    }
    static String checkedName(String value) {
        String name=value==null?"Meu perfil":value.trim();
        if(name.length()>80)throw new IllegalArgumentException("Nome do perfil deve ter até 80 caracteres.");
        return name.isEmpty()?"Meu perfil":name;
    }
    /** Already validated by AppFacade; queue disk I/O away from the UI. */
    static void saveNormalizedAsync(Context context,JSONObject normalized) {
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY,normalized.toString()).apply();
    }
    public static AudioProfile fromJson(JSONObject json) {
        try {
            if (json.optInt("schemaVersion", 1) != 1) throw new IllegalArgumentException("Versão de perfil não suportada.");
            if (json.optInt("sampleRate", 48000) != 48000) throw new IllegalArgumentException("O motor usa 48.000 Hz.");
            AudioProfile defaults = AudioProfile.defaultProfile();
            AudioProfile.Builder b = defaults.toBuilder()
                    .masterGain(number(json, "masterGain", defaults.getMasterGain()))
                    .muted(json.optBoolean("muted", defaults.isMuted()))
                    .bypass(json.optBoolean("bypass", defaults.isBypass()))
                    .inputMode(AudioProfile.InputMode.valueOf(json.optString("inputMode", "NATIVE_5_1")))
                    .upmixCenterGain(number(json, "upmixCenterGain", defaults.getUpmixCenterGain()))
                    .upmixSurroundGain(number(json, "upmixSurroundGain", defaults.getUpmixSurroundGain()))
                    .upmixBassGain(number(json, "upmixBassGain", defaults.getUpmixBassGain()))
                    .upmixDifference(number(json, "upmixDifference", defaults.getUpmixDifference()))
                    .upmixBassCutoffHz(number(json, "upmixBassCutoffHz", defaults.getUpmixBassCutoffHz()))
                    .frontCrossoverEnabled(json.optBoolean("frontCrossoverEnabled",defaults.isFrontCrossoverEnabled()))
                    .frontCutoffHz(number(json,"frontCutoffHz",defaults.getFrontCutoffHz()))
                    .frontBassSend(number(json,"frontBassSend",defaults.getFrontBassSend()))
                    .lfeSubsonicEnabled(json.optBoolean("lfeSubsonicEnabled",defaults.isLfeSubsonicEnabled()))
                    .lfeSubsonicHz(number(json,"lfeSubsonicHz",defaults.getLfeSubsonicHz()))
                    .swapCenterLfe(json.optBoolean("swapCenterLfe",defaults.isSwapCenterLfe()))
                    .lfeEqEnabled(json.optBoolean("lfeEqEnabled", defaults.isLfeEqEnabled()))
                    .lfeHeadroom(number(json, "lfeHeadroom", defaults.getLfeHeadroom()))
                    .automaticLfeHeadroom(json.optBoolean("automaticLfeHeadroom", defaults.isAutomaticLfeHeadroom()))
                    .surroundCrossoverEnabled(json.optBoolean("surroundCrossoverEnabled", defaults.isSurroundCrossoverEnabled()))
                    .surroundCutoffHz(number(json, "surroundCutoffHz", defaults.getSurroundCutoffHz()))
                    .surroundBassSend(number(json, "surroundBassSend", defaults.getSurroundBassSend()))
                    .centerBassCopyEnabled(json.optBoolean("centerBassCopyEnabled", defaults.isCenterBassCopyEnabled()))
                    .centerBassCutoffHz(number(json, "centerBassCutoffHz", defaults.getCenterBassCutoffHz()))
                    .centerBassSend(number(json, "centerBassSend", defaults.getCenterBassSend()));
            JSONArray trims = array(json, "channelTrim", "channelTrims", 6);
            JSONArray delays = array(json, "delaySamples", "delaysSamples", 6);
            JSONArray gains = array(json, "lfeEqGainDb", "lfeEqGainsDb", 9);
            JSONArray frequencies = array(json, "lfeEqFrequenciesHz", null, 9);
            JSONArray widths = array(json, "lfeEqQ", null, 9);
            for (int c = 0; c < 6; c++) {
                if (trims != null) b.channelTrim(c, (float) trims.getDouble(c));
                if (delays != null) {
                    double value = delays.getDouble(c);
                    if (value != Math.rint(value)) throw new IllegalArgumentException("Atrasos precisam ser amostras inteiras.");
                    b.delaySamples(c, (int) value);
                }
            }
            for (int i = 0; i < 9; i++) {
                if (gains != null) b.lfeEqGainDb(i, (float) gains.getDouble(i));
                if (frequencies != null) b.lfeEqFrequencyHz(i, (float) frequencies.getDouble(i));
                if (widths != null) b.lfeEqQ(i, (float) widths.getDouble(i));
            }
            return b.build();
        } catch (JSONException e) { throw new IllegalArgumentException("JSON de perfil inválido: " + e.getMessage(), e); }
    }
    public static JSONObject toJson(AudioProfile p, String name) {
        try {
            JSONArray trims = new JSONArray(), delays = new JSONArray(), gains = new JSONArray(), frequencies = new JSONArray(), widths = new JSONArray();
            for (int c = 0; c < 6; c++) { trims.put(p.getChannelTrim(c)); delays.put(p.getDelaySamples(c)); }
            for (int i = 0; i < 9; i++) { gains.put(p.getLfeEqGainDb(i)); frequencies.put(p.getLfeEqFrequencyHz(i)); widths.put(p.getLfeEqQ(i)); }
            return new JSONObject().put("schemaVersion", 1).put("name", name).put("sampleRate", 48000)
                    .put("masterGain", p.getMasterGain()).put("muted", p.isMuted()).put("bypass", p.isBypass())
                    .put("inputMode", p.getInputMode().name()).put("channelTrim", trims).put("delaySamples", delays)
                    .put("upmixCenterGain", p.getUpmixCenterGain()).put("upmixSurroundGain", p.getUpmixSurroundGain())
                    .put("upmixBassGain", p.getUpmixBassGain()).put("upmixDifference", p.getUpmixDifference())
                    .put("upmixBassCutoffHz", p.getUpmixBassCutoffHz())
                    .put("frontCrossoverEnabled",p.isFrontCrossoverEnabled()).put("frontCutoffHz",p.getFrontCutoffHz())
                    .put("frontBassSend",p.getFrontBassSend()).put("lfeSubsonicEnabled",p.isLfeSubsonicEnabled())
                    .put("lfeSubsonicHz",p.getLfeSubsonicHz())
                    .put("swapCenterLfe",p.isSwapCenterLfe())
                    .put("lfeEqEnabled", p.isLfeEqEnabled()).put("lfeEqGainDb", gains).put("lfeEqFrequenciesHz", frequencies).put("lfeEqQ", widths)
                    .put("lfeHeadroom", p.getLfeHeadroom()).put("automaticLfeHeadroom", p.isAutomaticLfeHeadroom())
                    .put("effectiveLfeHeadroom", p.getEffectiveLfeHeadroom())
                    .put("surroundCrossoverEnabled", p.isSurroundCrossoverEnabled()).put("surroundCutoffHz", p.getSurroundCutoffHz()).put("surroundBassSend", p.getSurroundBassSend())
                    .put("centerBassCopyEnabled", p.isCenterBassCopyEnabled()).put("centerBassCutoffHz", p.getCenterBassCutoffHz()).put("centerBassSend", p.getCenterBassSend());
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }
    private static float number(JSONObject json, String key, float fallback) throws JSONException {
        return json.has(key) ? (float) json.getDouble(key) : fallback;
    }
    private static JSONArray array(JSONObject json, String primary, String alias, int length) throws JSONException {
        String key = json.has(primary) ? primary : alias != null && json.has(alias) ? alias : null;
        if (key == null) return null;
        JSONArray a = json.getJSONArray(key);
        if (a.length() != length) throw new IllegalArgumentException(key + " deve ter " + length + " valores.");
        return a;
    }
}
