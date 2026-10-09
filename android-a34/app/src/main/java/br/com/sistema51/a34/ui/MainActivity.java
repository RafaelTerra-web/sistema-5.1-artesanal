package br.com.sistema51.a34.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import br.com.sistema51.a34.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native Android control surface; audio processing never runs on the UI thread. */
public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(7, 27, 32);
    private static final int SURFACE = Color.rgb(16, 43, 50);
    private static final int RAISED = Color.rgb(24, 55, 64);
    private static final int TEAL = Color.rgb(100, 221, 208);
    private static final int AMBER = Color.rgb(255, 197, 122);
    private static final int WHITE = Color.rgb(242, 247, 246);
    private static final int MUTED = Color.rgb(165, 190, 195);
    private static final int RED = Color.rgb(255, 135, 133);
    private static final int IMPORT_PROFILE = 41;
    private static final int EXPORT_PROFILE = 42;
    private static final int TEST_FILE = 43;
    private static final int EXPORT_REPORT = 44;
    private static final int EXPORT_AUDIO = 45;
    private static final int AUDIO_PERMISSION = 70;
    private static final int NOTIFICATION_PERMISSION = 71;
    private static final String[] CHANNELS = {"FL", "FR", "C", "LFE", "SL", "SR"};
    private static final String[] CHANNEL_NAMES = {"Frontal E", "Frontal D", "Central", "Subwoofer", "Surround E", "Surround D"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService documentWorker = Executors.newSingleThreadExecutor();
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateStatus();
            main.postDelayed(this, 800);
        }
    };
    private final Runnable applyEdits = new Runnable() {
        @Override public void run() { applyProfile(); }
    };
    private AppBridge bridge;
    private JSONObject profile;
    private LinearLayout content;
    private FrameLayout pageHost;
    private final LinearLayout[] pages = new LinearLayout[3];
    private final ScrollView[] scrolls = new ScrollView[3];
    private final int[] scrollPositions = new int[3];
    private Button[] tabs;
    private int selectedTab;
    private boolean dirty;
    private boolean testing;
    private boolean editsPending;
    private boolean syncingControls;
    private boolean delaysUnlocked;
    private Button delayLockButton;
    private TextView delayLockStatus;
    private final List<SliderBinding> delayControls = new ArrayList<>();
    private boolean usbExpanded, reportExpanded;
    private boolean notifyWhenRunning;
    private String testReport;
    private TextView heroTitle, heroDetail, connectionStatus, inputText, outputText;
    private TextView meterInfo, clippingText, usbDetails, usbSummary, reportText, reportDetails, profileState;
    private TextView channelBadge, modeBadge, performanceText;
    private Button startButton, stopButton, testButton, fileButton, exportAudioButton, hardwareButton;
    private final List<SliderBinding> masterControls = new ArrayList<>();
    private final List<ToggleBinding> sharedToggles = new ArrayList<>();
    private String renderedReport;
    private long nextUsbRenderMillis;
    private LevelView[] meters;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        bridge = AppBridge.create(this);
        profile = copy(bridge.loadProfile());
        if (state != null) {
            try { profile = new JSONObject(state.getString("profile", profile.toString())); }
            catch (JSONException ignored) { }
            selectedTab = state.getInt("tab", 0);
            dirty = state.getBoolean("dirty", false);
            testReport = state.getString("report");
            notifyWhenRunning = state.getBoolean("notifyWhenRunning", false);
            usbExpanded = state.getBoolean("usbExpanded", false);
            reportExpanded = state.getBoolean("reportExpanded", false);
            for (int i = 0; i < 3; i++) scrollPositions[i] = state.getInt("scroll" + i, 0);
            // Configuration changes preserve unsaved edits and the active engine profile.
            try { bridge.applyProfile(copy(profile)); }
            catch (RuntimeException error) { showError(error); }
        }
        makeShell();
        showTab(selectedTab);
    }

    @Override protected void onResume() {
        super.onResume();
        main.removeCallbacks(refresh);
        refresh.run();
    }

    @Override protected void onPause() {
        main.removeCallbacks(refresh);
        setDelayEditing(false);
        // Commit the most recent gesture before leaving; never lose the debounce tail.
        if (editsPending) applyProfile();
        super.onPause();
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(refresh);
        main.removeCallbacks(applyEdits);
        documentWorker.shutdownNow();
        bridge.close();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("profile", profile.toString());
        out.putInt("tab", selectedTab);
        out.putBoolean("dirty", dirty);
        // A recreated facade may cancel its test; do not restore a permanent "running" message.
        out.putString("report", testing ? null : testReport);
        out.putBoolean("notifyWhenRunning", notifyWhenRunning);
        out.putBoolean("usbExpanded", usbExpanded);
        out.putBoolean("reportExpanded", reportExpanded);
        for (int i = 0; i < 3; i++) out.putInt("scroll" + i, scrolls[i] == null ? scrollPositions[i] : scrolls[i].getScrollY());
    }

    private void makeShell() {
        LinearLayout shell = column();
        shell.setBackgroundColor(BG);
        // Target 35+ draws edge-to-edge. Keep controls clear of cutouts and gesture bars.
        shell.setOnApplyWindowInsetsListener((v, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
                bottom = Math.max(bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(left, top, right, bottom);
            return insets;
        });

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(18), dp(20), dp(14));
        LinearLayout title = column();
        TextView overline = text("SISTEMA 5.1 ARTESANAL", 10, TEAL);
        overline.setLetterSpacing(.15f);
        title.addView(overline);
        TextView name = text("A34 DSP", 30, WHITE);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.addView(name);
        title.addView(text("48 kHz  ·  USB multicanal  ·  v" + appVersion(), 12, MUTED));
        header.addView(title, weighted());
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_surround);
        icon.setContentDescription("Sistema surround de seis canais");
        header.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        shell.addView(header);

        LinearLayout tabRow = row();
        tabRow.setPadding(dp(16), 0, dp(16), dp(10));
        tabs = new Button[3];
        String[] labels = {"Painel", "Perfil", "Diagnóstico"};
        for (int i = 0; i < 3; i++) {
            final int tab = i;
            tabs[i] = button(labels[i], false);
            tabs[i].setTextSize(13);
            tabs[i].setOnClickListener(v -> showTab(tab));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1);
            if (i > 0) p.leftMargin = dp(6);
            tabRow.addView(tabs[i], p);
        }
        shell.addView(tabRow);
        pageHost = new FrameLayout(this);
        shell.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(shell);
        shell.requestApplyInsets();
    }

    private void showTab(int index) {
        if (index != 1) setDelayEditing(false);
        View focused = getCurrentFocus();
        if (focused != null) {
            InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            focused.clearFocus();
        }
        selectedTab = Math.max(0, Math.min(2, index));
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setBackground(shape(i == selectedTab ? TEAL : RAISED, 12));
            tabs[i].setTextColor(i == selectedTab ? BG : MUTED);
            tabs[i].setSelected(i == selectedTab);
        }
        if (pages[selectedTab] == null) {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true); scroll.setClipToPadding(false);
            content = column(); content.setPadding(dp(16), dp(4), dp(16), dp(24));
            content.setFocusableInTouchMode(true);
            pages[selectedTab] = content; scrolls[selectedTab] = scroll;
            scroll.addView(content); pageHost.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
            if (selectedTab == 0) buildDashboard();
            else if (selectedTab == 1) buildProfile();
            else buildDiagnostics();
            final int tab = selectedTab;
            scroll.post(() -> scroll.scrollTo(0, scrollPositions[tab]));
        }
        content = pages[selectedTab];
        for (int i = 0; i < 3; i++) if (scrolls[i] != null) scrolls[i].setVisibility(i == selectedTab ? View.VISIBLE : View.GONE);
        syncSharedControls();
        updateStatus();
    }

    private void rebuildProfilePage() {
        setDelayEditing(false);
        delayControls.clear();
        if (scrolls[1] != null) {
            scrollPositions[1] = scrolls[1].getScrollY();
            pageHost.removeView(scrolls[1]); pages[1] = null; scrolls[1] = null;
            masterControls.removeIf(binding -> binding.page == 1);
            sharedToggles.removeIf(binding -> binding.page == 1);
        }
        showTab(1);
    }

    private void buildDashboard() {
        LinearLayout hero = card();
        connectionStatus = text("AGUARDANDO USB", 10, AMBER);
        connectionStatus.setLetterSpacing(.1f);
        hero.addView(connectionStatus);
        LinearLayout badges = row();
        badges.setPadding(0, dp(12), 0, 0);
        channelBadge = badge("USB não detectado", MUTED);
        badges.addView(channelBadge);
        modeBadge = badge("Perfil 5.1", TEAL);
        LinearLayout.LayoutParams badgeGap = new LinearLayout.LayoutParams(-2, -2); badgeGap.leftMargin = dp(8);
        badges.addView(modeBadge, badgeGap); hero.addView(badges);
        heroTitle = text("Aguardando interface USB", 24, WHITE);
        heroTitle.setPadding(0, dp(10), 0, dp(8));
        heroTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hero.addView(heroTitle);
        heroDetail = text("Conecte a CM6206 pelo hub USB-C alimentado para verificar a saída multicanal.", 14, MUTED);
        hero.addView(heroDetail);
        LinearLayout actions = row();
        actions.setPadding(0, dp(18), 0, 0);
        startButton = button("Iniciar DSP", true);
        startButton.setOnClickListener(v -> startAudio());
        stopButton = button("Parar", false);
        stopButton.setOnClickListener(v -> {
            try { bridge.stop(); updateStatus(); }
            catch (RuntimeException error) { showError(error); }
        });
        actions.addView(startButton, weighted());
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(dp(88), dp(48));
        stopParams.leftMargin = dp(8);
        actions.addView(stopButton, stopParams);
        hero.addView(actions);
        addCard(hero);

        LinearLayout controls = card();
        heading(controls, "Volume e processamento", "Toque no valor para ajustar com precisão.");
        addMaster(controls);
        addToggle(controls, "Silenciar", "Interrompe o sinal de saída.", "muted", false);
        addToggle(controls, "Bypass", "Mantém volume e ganho por canal.", "bypass", false);
        addCard(controls);

        LinearLayout levels = card();
        heading(levels, "Níveis de saída", "FL · FR · C · LFE · SL · SR");
        LinearLayout meterRow = row();
        meters = new LevelView[6];
        for (int i = 0; i < 6; i++) {
            meters[i] = new LevelView(CHANNELS[i], i == 3 ? AMBER : TEAL);
            meterRow.addView(meters[i], new LinearLayout.LayoutParams(0, dp(148), 1));
        }
        levels.addView(meterRow);
        meterInfo = text("Sem sinal de áudio. Medidores aguardam o motor.", 12, MUTED);
        meterInfo.setPadding(0, dp(10), 0, 0);
        levels.addView(meterInfo);
        clippingText = text("", 12, RED);
        clippingText.setPadding(0, dp(6), 0, 0);
        levels.addView(clippingText);
        addCard(levels);

        LinearLayout route = card();
        heading(route, "Caminho de áudio", "PCM USB → processamento → saída 5.1");
        route.addView(text("ENTRADA", 10, TEAL));
        inputText = text("Sem captura ativa", 14, WHITE);
        inputText.setPadding(0, dp(4), 0, dp(14)); route.addView(inputText);
        route.addView(text("SAÍDA", 10, TEAL));
        outputText = text("Sem reprodução ativa", 14, WHITE);
        outputText.setPadding(0, dp(4), 0, dp(12)); route.addView(outputText);
        route.addView(text("O modo 5.1 preserva arquivos de seis canais. A entrada PCM estéreo pode usar upmix; AC-3 óptico ao vivo aguarda validação.", 12, MUTED));
        addCard(route);
        Button diagnostics = button("Abrir diagnóstico e testes", false);
        diagnostics.setOnClickListener(v -> showTab(2));
        content.addView(diagnostics, fullButton());
    }

    private void buildProfile() {
        LinearLayout presets = card();
        heading(presets, "Seu perfil de áudio", "Ajustes são aplicados e salvos automaticamente neste aparelho.");
        EditText profileName = new EditText(this);
        profileName.setSingleLine(true);
        profileName.setTextColor(WHITE);
        profileName.setHintTextColor(MUTED);
        profileName.setHint("Nome do perfil");
        profileName.setText(profile.optString("name", "Referência PC"));
        profileName.setFilters(new InputFilter[] {new InputFilter.LengthFilter(80)});
        profileName.setBackgroundTintList(ColorStateList.valueOf(TEAL));
        profileName.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) { put("name", text.toString()); changed(); }
        });
        presets.addView(profileName, new LinearLayout.LayoutParams(-1, dp(52)));
        profileState = text(dirty ? "Aplicando alterações…" : "Perfil salvo neste aparelho.", 12, dirty ? AMBER : MUTED);
        profileState.setPadding(0, dp(8), 0, dp(12));
        presets.addView(profileState);
        LinearLayout saveRow = row();
        Button save = button("Salvar agora", true);
        save.setOnClickListener(v -> {
            put("name", profileName.getText().toString().trim().isEmpty() ? "Meu perfil" : profileName.getText().toString().trim());
            try {
                applyProfile(); bridge.saveProfile(copy(profile)); dirty = false;
                profileState.setText("Perfil salvo neste aparelho."); profileState.setTextColor(TEAL);
                toast("Perfil salvo.");
            } catch (RuntimeException error) { showError(error); }
        });
        Button reset = button("Restaurar", false);
        reset.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Restaurar referência PC?")
                .setMessage("Os ajustes atuais serão substituídos pelos valores de referência PC e salvos neste aparelho.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Restaurar", (dialog, which) -> {
                    profile = AppBridge.defaultProfile(); changed(); rebuildProfilePage();
                }).show());
        saveRow.addView(save, weighted());
        LinearLayout.LayoutParams rp = weighted(); rp.leftMargin = dp(8);
        saveRow.addView(reset, rp); presets.addView(saveRow);
        LinearLayout transfer = row(); transfer.setPadding(0, dp(8), 0, 0);
        Button imp = button("Importar JSON", false);
        imp.setOnClickListener(v -> openDocument(IMPORT_PROFILE, "application/json"));
        Button exp = button("Exportar JSON", false);
        exp.setOnClickListener(v -> {
            put("name", profileName.getText().toString().trim());
            createDocument(EXPORT_PROFILE, "application/json", "a34-perfil.json");
        });
        transfer.addView(imp, weighted());
        LinearLayout.LayoutParams ep = weighted(); ep.leftMargin = dp(8);
        transfer.addView(exp, ep); presets.addView(transfer);
        addCard(presets);

        LinearLayout mode = card();
        heading(mode, "Entrada e ganho", "Upmix estéreo é uma matriz local; não recupera surround original.");
        LinearLayout modes = row();
        Button nativeMode = button("5.1 nativo", "NATIVE_5_1".equals(profile.optString("inputMode", "NATIVE_5_1")));
        Button stereoMode = button("Upmix estéreo", "STEREO_UPMIX".equals(profile.optString("inputMode")));
        nativeMode.setOnClickListener(v -> chooseMode("NATIVE_5_1", nativeMode, stereoMode));
        stereoMode.setOnClickListener(v -> chooseMode("STEREO_UPMIX", nativeMode, stereoMode));
        modes.addView(nativeMode, weighted());
        LinearLayout.LayoutParams mp = weighted(); mp.leftMargin = dp(8);
        modes.addView(stereoMode, mp); mode.addView(modes);
        addMaster(mode);
        addToggle(mode, "Silenciar saída", "Mute em todos os seis canais.", "muted", false);
        addToggle(mode, "Bypass do DSP", "Volume e trims continuam ativos.", "bypass", false);
        addCard(mode);

        LinearLayout upmix = card();
        heading(upmix, "Upmix estéreo", "Só atua em entrada estéreo. Preserva FL/FR e não cria uma gravação 5.1 original.");
        LinearLayout upmixPresets = row();
        Button fill = button("Preencher caixas", false);
        Button ambience = button("Ambiência", false);
        fill.setOnClickListener(v -> {
            put("upmixCenterGain", 1); put("upmixSurroundGain", .5); put("upmixBassGain", .5);
            put("upmixDifference", 0); put("upmixBassCutoffHz", 120); changed(); rebuildProfilePage();
        });
        ambience.setOnClickListener(v -> {
            put("upmixCenterGain", .7071); put("upmixSurroundGain", .5); put("upmixBassGain", .25);
            put("upmixDifference", 1); put("upmixBassCutoffHz", 80); changed(); rebuildProfilePage();
        });
        upmixPresets.addView(fill, weighted());
        LinearLayout.LayoutParams ap = weighted(); ap.leftMargin = dp(8);
        upmixPresets.addView(ambience, ap); upmix.addView(upmixPresets);
        addSlider(upmix, "Central no upmix", (float) profile.optDouble("upmixCenterGain", 1) * 100, 0, 100, 1, "%", v -> { put("upmixCenterGain", v / 100); changed(); });
        addSlider(upmix, "Traseiras no upmix", (float) profile.optDouble("upmixSurroundGain", .5) * 100, 0, 100, 1, "%", v -> { put("upmixSurroundGain", v / 100); changed(); });
        addSlider(upmix, "Graves gerados", (float) profile.optDouble("upmixBassGain", .5) * 100, 0, 100, 1, "%", v -> { put("upmixBassGain", v / 100); changed(); });
        addSlider(upmix, "Separação da ambiência", (float) profile.optDouble("upmixDifference", 0) * 100, 0, 100, 1, "%", v -> { put("upmixDifference", v / 100); changed(); });
        addSlider(upmix, "Corte dos graves do upmix", (float) profile.optDouble("upmixBassCutoffHz", 120), 40, 160, 1, "Hz", v -> { put("upmixBassCutoffHz", v); changed(); });
        upmix.addView(text("Com separação em 100%, voz igual em L/R fica fora das traseiras. Use trims para equilibrar os amplificadores; esta matriz não substitui a calibração.", 12, MUTED));
        addCard(upmix);

        LinearLayout timing = card();
        heading(timing, "Atraso e ganho por canal", "A calibração dos atrasos fica protegida. O ganho por canal continua disponível.");
        delayLockStatus = text("Atrasos bloqueados para evitar alterações acidentais.", 13, MUTED);
        timing.addView(delayLockStatus);
        delayLockButton = button("Editar atrasos", false);
        delayLockButton.setOnClickListener(v -> {
            if (delaysUnlocked) { setDelayEditing(false); return; }
            new AlertDialog.Builder(this).setTitle("Desbloquear os atrasos?")
                    .setMessage("Esses valores fazem parte da calibração das caixas. A edição será bloqueada novamente ao sair desta aba ou deixar o app em segundo plano.")
                    .setNegativeButton("Manter bloqueados", null)
                    .setPositiveButton("Desbloquear edição", (dialog, which) -> {
                        if (!isFinishing() && !isDestroyed() && selectedTab == 1) setDelayEditing(true);
                    }).show();
        });
        timing.addView(delayLockButton, fullButton());
        for (int i = 0; i < 6; i++) {
            final int channel = i;
            TextView title = text(CHANNELS[i] + "  ·  " + CHANNEL_NAMES[i], 14, i == 3 ? AMBER : TEAL);
            title.setPadding(0, dp(i == 0 ? 2 : 14), 0, dp(3));
            timing.addView(title);
            float delay = (float) (arrayValue("delaySamples", i, 0) / 48.0);
            addSlider(timing, "Atraso", delay, 0, 250, .1f, "ms", value -> {
                if (!delaysUnlocked) return;
                putArray("delaySamples", channel, Math.round(value * 48)); changed();
            }, true);
            float trim = linearToDb(arrayValue("channelTrim", i, 1));
            addSlider(timing, "Trim", trim, -24, 12, .5f, "dB", value -> {
                putArray("channelTrim", channel, dbToLinear(value)); changed();
            });
        }
        addCard(timing);

        LinearLayout bass = card();
        heading(bass, "Gerenciamento de graves", "As cópias de graves são somadas ao LFE. A margem evita ganho excessivo.");
        addToggle(bass,"Trocar CEN/BASS na saída USB","Para central na saída R do módulo: troca apenas os slots enviados à placa.","swapCenterLfe",false);
        addToggle(bass,"Crossover frontais e central","Retira graves de FL/FR/FC e envia ao LFE, antes dos atrasos.","frontCrossoverEnabled",false);
        addSlider(bass,"Corte frontais/central",(float)profile.optDouble("frontCutoffHz",90),40,160,1,"Hz",v->{put("frontCutoffHz",v);changed();});
        addSlider(bass,"Envio frontais/central → LFE",linearToDb(profile.optDouble("frontBassSend",1)),-24,0,.5f,"dB",v->{put("frontBassSend",dbToLinear(v));changed();});
        addToggle(bass,"Filtro subsônico no LFE","Reduz infragraves abaixo do corte; não limita potência do amplificador.","lfeSubsonicEnabled",false);
        addSlider(bass,"Corte subsônico",(float)profile.optDouble("lfeSubsonicHz",20),10,40,1,"Hz",v->{put("lfeSubsonicHz",v);changed();});
        addToggle(bass, "Crossover dos surrounds", "Passa-altas em SL/SR; envia graves ao subwoofer.", "surroundCrossoverEnabled", true);
        addSlider(bass, "Corte dos surrounds", (float) profile.optDouble("surroundCutoffHz", 90), 40, 120, 1, "Hz", value -> { put("surroundCutoffHz", value); changed(); });
        addSlider(bass, "Envio surround → LFE", linearToDb(profile.optDouble("surroundBassSend", 1)), -24, 0, .5f, "dB", value -> { put("surroundBassSend", dbToLinear(value)); changed(); });
        addToggle(bass, "Copiar graves da central", "Mantém a central inteira e copia o passa-baixas para LFE.", "centerBassCopyEnabled", false);
        bass.addView(text("Com o crossover frontal ativo, a cópia separada da central é ignorada para não somar os mesmos graves duas vezes.",12,MUTED));
        addSlider(bass, "Corte da central", (float) profile.optDouble("centerBassCutoffHz", 120), 40, 120, 1, "Hz", value -> { put("centerBassCutoffHz", value); changed(); });
        addSlider(bass, "Envio central → LFE", linearToDb(profile.optDouble("centerBassSend", 1)), -24, 0, .5f, "dB", value -> { put("centerBassSend", dbToLinear(value)); changed(); });
        addToggle(bass, "Margem automática no LFE", "Reduz o nível pelo pior caso da soma de graves e boosts do EQ.", "automaticLfeHeadroom", true);
        addSlider(bass, "Margem manual do LFE", linearToDb(profile.optDouble("lfeHeadroom", 1.0 / 3.0)), -36, 0, .5f, "dB", value -> { put("lfeHeadroom", dbToLinear(value)); changed(); });
        bass.addView(text("A margem manual só é usada com a margem automática desligada.", 12, MUTED));
        addCard(bass);

        LinearLayout eq = card();
        heading(eq, "Equalizador do subwoofer", "9 bandas paramétricas · ganhos em dB · Q editável por banda");
        addToggle(eq, "Ativar EQ no LFE", "A equalização atua no subwoofer.", "lfeEqEnabled", true);
        for (int i = 0; i < 9; i++) {
            final int band = i;
            double frequency = arrayValue("lfeEqFrequenciesHz", i, 20);
            addSlider(eq, fmt(frequency, 0) + " Hz", (float) arrayValue("lfeEqGainDb", i, 0), -12, 6, .5f, "dB", value -> {
                putArray("lfeEqGainDb", band, value); changed();
            });
            TextView bandEdit = text("Banda " + (i + 1) + "  ·  " + fmt(frequency, 0) + " Hz  ·  Q " + fmt(arrayValue("lfeEqQ", i, 2), 2) + "   Editar", 12, TEAL);
            bandEdit.setPadding(0, 0, 0, dp(10));
            bandEdit.setMinHeight(dp(48));
            bandEdit.setGravity(Gravity.CENTER_VERTICAL);
            bandEdit.setOnClickListener(v -> editBand(band));
            eq.addView(bandEdit);
        }
        addCard(eq);
    }

    private void buildDiagnostics() {
        LinearLayout usb = card();
        heading(usb, "Interface USB", "Confira a placa e autorize o acesso neste Android.");
        usbSummary = text("Consultando a conexão…", 14, WHITE);
        usbSummary.setPadding(0, 0, 0, dp(12)); usb.addView(usbSummary);
        Button permission = button("Permissão USB", true);
        permission.setOnClickListener(v -> {
            try { bridge.requestUsbPermission(); updateStatus(); }
            catch (RuntimeException error) { showError(error); }
        });
        usb.addView(permission, fullButton());
        hardwareButton = button("Verificar placa", false);
        hardwareButton.setOnClickListener(v -> {
            if (isBusy()) return; testing = true; testReport = "Consultando a placa USB…"; updateStatus();
            try {
                bridge.hardwareTest(new AppBridge.Callback() {
                    @Override public void onComplete(String report) { showResult(report); }
                    @Override public void onError(String message) { showResult("Falha no diagnóstico:\n" + message); }
                });
            } catch (RuntimeException error) { showResult("Falha no diagnóstico:\n" + error.getMessage()); }
        });
        usb.addView(hardwareButton, fullButton());
        usbDetails = text("", 11, MUTED);
        usbDetails.setTypeface(Typeface.MONOSPACE);
        usbDetails.setTextIsSelectable(true);
        usbDetails.setPadding(0, dp(14), 0, 0);
        usbDetails.setVisibility(usbExpanded ? View.VISIBLE : View.GONE);
        Button rawUsb = button(usbExpanded ? "Ocultar detalhes técnicos" : "Detalhes técnicos USB", false);
        rawUsb.setOnClickListener(v -> {
            usbExpanded = !usbExpanded; usbDetails.setVisibility(usbExpanded ? View.VISIBLE : View.GONE);
            rawUsb.setText(usbExpanded ? "Ocultar detalhes técnicos" : "Detalhes técnicos USB");
            nextUsbRenderMillis = 0; updateStatus();
        });
        usb.addView(rawUsb, fullButton()); usb.addView(usbDetails);
        addCard(usb);

        LinearLayout performance = card();
        heading(performance, "Estabilidade da sessão", "Dados da captura e reprodução PCM em execução.");
        performanceText = text("Inicie o DSP para acompanhar a carga e interrupções de saída.", 13, MUTED);
        performanceText.setVisibility(View.GONE);
        Button performanceToggle = button("Mostrar desempenho", false);
        performanceToggle.setOnClickListener(v -> {
            boolean open = performanceText.getVisibility() != View.VISIBLE;
            performanceText.setVisibility(open ? View.VISIBLE : View.GONE);
            performanceToggle.setText(open ? "Ocultar desempenho" : "Mostrar desempenho"); updateStatus();
        });
        performance.addView(performanceToggle, fullButton()); performance.addView(performanceText);
        addCard(performance);

        LinearLayout tests = card();
        heading(tests, "Testes do DSP", "Processamento de arquivos sem reproduzir áudio. O autoteste verifica os seis canais e os atrasos.");
        testButton = button("Executar autoteste do DSP", true);
        testButton.setOnClickListener(v -> runTest(null));
        tests.addView(testButton, fullButton());
        fileButton = button("Escolher WAV ou AC-3", false);
        fileButton.setOnClickListener(v -> openDocument(TEST_FILE, "*/*"));
        tests.addView(fileButton, fullButton());
        reportText = text("Nenhum teste executado.", 14, MUTED);
        reportText.setTextIsSelectable(true);
        reportText.setPadding(0, dp(14), 0, dp(10));
        tests.addView(reportText);
        reportDetails = text("", 11, MUTED);
        reportDetails.setTypeface(Typeface.MONOSPACE); reportDetails.setTextIsSelectable(true);
        reportDetails.setVisibility(reportExpanded ? View.VISIBLE : View.GONE);
        Button details = button(reportExpanded ? "Ocultar relatório técnico" : "Relatório técnico", false);
        details.setOnClickListener(v -> {
            reportExpanded = !reportExpanded; reportDetails.setVisibility(reportExpanded ? View.VISIBLE : View.GONE);
            details.setText(reportExpanded ? "Ocultar relatório técnico" : "Relatório técnico"); renderReport(currentReport());
        });
        tests.addView(details, fullButton()); tests.addView(reportDetails);
        Button export = button("Exportar relatório JSON", false);
        export.setOnClickListener(v -> createDocument(EXPORT_REPORT, "application/json", "a34-diagnostico.json"));
        tests.addView(export, fullButton());
        exportAudioButton = button("Salvar WAV processado", false);
        exportAudioButton.setOnClickListener(v -> createDocument(EXPORT_AUDIO, "audio/wav", "a34-dsp-51.wav"));
        tests.addView(exportAudioButton, fullButton());
        addCard(tests);

        LinearLayout codecs = card();
        heading(codecs, "Decodificadores deste Android", "O teste de arquivo relata formato e duração. A fidelidade do codec Samsung permanece experimental.");
        TextView codecInfo = text("", 11, MUTED);
        codecInfo.setTypeface(Typeface.MONOSPACE);
        codecInfo.setTextIsSelectable(true);
        codecInfo.setVisibility(View.GONE);
        Button codecsToggle = button("Consultar codecs AC-3", false);
        codecsToggle.setOnClickListener(v -> {
            boolean open = codecInfo.getVisibility() != View.VISIBLE;
            codecInfo.setVisibility(open ? View.VISIBLE : View.GONE);
            codecsToggle.setText(open ? "Ocultar codecs" : "Consultar codecs AC-3");
            if (open && codecInfo.getText().length() == 0) {
                codecsToggle.setEnabled(false); codecInfo.setText("Consultando…");
                documentWorker.submit(() -> {
                    String value = codecSummary();
                    main.post(() -> { if (!isDestroyed()) { codecInfo.setText(value); codecsToggle.setEnabled(true); } });
                });
            }
        });
        codecs.addView(codecsToggle, fullButton()); codecs.addView(codecInfo);
        addCard(codecs);
        LinearLayout remaining = card();
        heading(remaining, "Validação com o hardware", "Com a CM6206 ligada ao A34 por OTG ou hub:");
        remaining.addView(text("O USB informa capacidades; o teste óptico confirma o sinal recebido. Separação dos canais, latência e estabilidade ainda exigem uma sessão com fonte e amplificadores.", 13, MUTED));
        addCard(remaining);
    }

    private void addMaster(LinearLayout target) {
        SliderBinding binding = addSlider(target, "Volume geral", linearToDb(profile.optDouble("masterGain", .04)), -60, 0, .5f, "dB", value -> {
            put("masterGain", dbToLinear(value)); changed();
        });
        masterControls.add(binding);
    }

    private interface ValueChange { void set(float value); }

    private void setDelayEditing(boolean enabled) {
        delaysUnlocked = enabled;
        for (SliderBinding binding : delayControls) {
            binding.bar.setEnabled(enabled); binding.value.setEnabled(enabled);
            binding.value.setClickable(enabled); binding.value.setFocusable(enabled);
            binding.bar.setAlpha(enabled ? 1f : .55f);
            binding.value.setAlpha(enabled ? 1f : .7f);
        }
        if (delayLockButton != null) delayLockButton.setText(enabled ? "Bloquear atrasos" : "Editar atrasos");
        if (delayLockStatus != null) {
            delayLockStatus.setText(enabled ? "Edição dos atrasos habilitada nesta aba." : "Atrasos bloqueados para evitar alterações acidentais.");
            delayLockStatus.setTextColor(enabled ? AMBER : MUTED);
        }
    }

    private SliderBinding addSlider(LinearLayout parent, String label, float initial, float min, float max,
                           float step, String unit, ValueChange onValue) {
        return addSlider(parent, label, initial, min, max, step, unit, onValue, false);
    }

    private SliderBinding addSlider(LinearLayout parent, String label, float initial, float min, float max,
                           float step, String unit, ValueChange onValue, boolean protectedDelay) {
        LinearLayout labels = row();
        labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(label, 13, WHITE);
        labels.addView(name, weighted());
        TextView valueText = text(formatValue(initial, unit), 13, TEAL);
        valueText.setMinHeight(dp(48));
        valueText.setMinWidth(dp(78));
        valueText.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        labels.addView(valueText);
        parent.addView(labels);
        SeekBar bar = new SeekBar(this);
        bar.setMax(Math.round((max - min) / step));
        bar.setProgress(Math.round((Math.max(min, Math.min(max, initial)) - min) / step));
        bar.setProgressTintList(ColorStateList.valueOf(TEAL));
        bar.setThumbTintList(ColorStateList.valueOf(TEAL));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(RAISED));
        bar.setPadding(dp(4), 0, dp(4), 0);
        bar.setContentDescription(label + " em " + unit);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar view, int progress, boolean fromUser) {
                if (!fromUser) return;
                if (protectedDelay && !delaysUnlocked) return;
                float value = min + progress * step;
                valueText.setText(formatValue(value, unit));
                onValue.set(value);
            }
            @Override public void onStartTrackingTouch(SeekBar view) { }
            @Override public void onStopTrackingTouch(SeekBar view) { applyProfile(); }
        });
        parent.addView(bar, new LinearLayout.LayoutParams(-1, dp(48)));
        valueText.setOnClickListener(v -> {
            if (protectedDelay && !delaysUnlocked) return;
            EditText edit = numberInput(Float.parseFloat(valueText.getText().toString().split(" ")[0].replace(',', '.')));
            new AlertDialog.Builder(this).setTitle(label + " (" + unit + ")")
                    .setMessage("Faixa: " + formatValue(min, unit) + " a " + formatValue(max, unit))
                    .setView(edit).setNegativeButton("Cancelar", null)
                    .setPositiveButton("Aplicar", (dialog, which) -> {
                        try {
                            if (protectedDelay && !delaysUnlocked) { toast("Os atrasos estão bloqueados."); return; }
                            float entered = Float.parseFloat(edit.getText().toString().trim().replace(',', '.'));
                            if (!Float.isFinite(entered) || entered < min || entered > max) {
                                toast("Valor fora da faixa permitida."); return;
                            }
                            valueText.setText(formatValue(entered, unit));
                            bar.setProgress(Math.round((entered - min) / step));
                            onValue.set(entered); applyProfile();
                        } catch (NumberFormatException error) { toast("Informe um número válido."); }
                    }).show();
        });
        SliderBinding binding = new SliderBinding(bar, valueText, selectedTab, min, step, unit);
        if (protectedDelay) { delayControls.add(binding); setDelayEditing(delaysUnlocked); }
        return binding;
    }

    private void addToggle(LinearLayout target, String title, String description, String key, boolean fallback) {
        LinearLayout row = row(); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(8));
        LinearLayout labels = column();
        labels.addView(text(title, 14, WHITE));
        TextView secondary = text(description, 12, MUTED);
        secondary.setPadding(0, dp(4), dp(8), 0); labels.addView(secondary);
        row.addView(labels, weighted());
        Switch toggle = new Switch(this);
        toggle.setMinHeight(dp(48)); toggle.setContentDescription(title);
        toggle.setChecked(profile.optBoolean(key, fallback));
        toggle.setThumbTintList(new ColorStateList(new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}}, new int[] {TEAL, MUTED}));
        toggle.setTrackTintList(ColorStateList.valueOf(RAISED));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (syncingControls) return;
            put(key, checked); changed(); applyProfile(); syncSharedControls();
        });
        if (key.equals("muted") || key.equals("bypass")) sharedToggles.add(new ToggleBinding(key, toggle, selectedTab));
        row.addView(toggle); target.addView(row);
    }

    private void editBand(int band) {
        LinearLayout form = column(); form.setPadding(dp(24), dp(8), dp(24), 0);
        form.addView(text("Frequência (Hz), de 10 a 200", 13, MUTED));
        EditText frequency = numberInput(arrayValue("lfeEqFrequenciesHz", band, 20)); form.addView(frequency);
        form.addView(text("Q, de 0,3 a 10", 13, MUTED));
        EditText q = numberInput(arrayValue("lfeEqQ", band, 2)); form.addView(q);
        new AlertDialog.Builder(this).setTitle("EQ · banda " + (band + 1)).setView(form)
                .setNegativeButton("Cancelar", null).setPositiveButton("Aplicar", (dialog, which) -> {
                    try {
                        double f = Double.parseDouble(frequency.getText().toString().trim().replace(',', '.'));
                        double quality = Double.parseDouble(q.getText().toString().trim().replace(',', '.'));
                        if (!Double.isFinite(f) || !Double.isFinite(quality) || f < 10 || f > 200 || quality < .3 || quality > 10) {
                            toast("Frequência ou Q fora da faixa permitida."); return;
                        }
                        putArray("lfeEqFrequenciesHz", band, f); putArray("lfeEqQ", band, quality);
                        changed(); applyProfile(); rebuildProfilePage();
                    } catch (NumberFormatException error) { toast("Informe números válidos."); }
                }).show();
    }

    private void updateStatus() {
        if (bridge == null || isDestroyed()) return;
        JSONObject s;
        try { s = bridge.snapshot(); }
        catch (RuntimeException error) { return; }
        if (s == null) return;
        boolean running = s.optBoolean("running", false);
        boolean busy = testing || s.optBoolean("busy", false);
        if (running && notifyWhenRunning) {
            notifyWhenRunning = false;
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
            }
        }
        if (heroTitle != null && selectedTab == 0) {
            boolean attached = s.optBoolean("usbAttached", false);
            setTextIfChanged(heroTitle, s.optString("title", running ? "DSP em execução" : "Aguardando interface USB"));
            setTextIfChanged(heroDetail, s.optString("detail", "Conecte a interface e confira os canais em Diagnóstico."));
            setTextIfChanged(connectionStatus, running ? "DSP EM EXECUÇÃO" : attached ? "USB CONECTADO · DSP PARADO" : "AGUARDANDO INTERFACE USB");
            connectionStatus.setTextColor(running ? TEAL : AMBER);
            startButton.setEnabled(!running && !busy && !s.optBoolean("loading", false)); stopButton.setEnabled(running || "starting".equals(s.optString("state")));
            setTextIfChanged(channelBadge, attached ? channelCapabilitySummary(s) : "USB não detectado");
            setTextIfChanged(modeBadge, "STEREO_UPMIX".equals(profile.optString("inputMode")) ? "Perfil upmix" : "Perfil 5.1");
            setTextIfChanged(inputText, s.optString("inputSummary", "Sem captura ativa"));
            setTextIfChanged(outputText, s.optString("outputSummary", "Sem reprodução ativa"));
            JSONArray levelData = s.optJSONArray("meters");
            boolean hasMeters = running && levelData != null && levelData.length() == 6;
            for (int i = 0; i < 6; i++) meters[i].setLevel(hasMeters ? (float) levelData.optDouble(i, 0) : 0, hasMeters);
            setTextIfChanged(meterInfo, hasMeters ? "Níveis RMS da saída PCM · dBFS" : "Sem sessão ativa. Os medidores aguardam áudio.");
            boolean clipping = s.optBoolean("clipping", false);
            setTextIfChanged(clippingText, clipping ? "Clipping detectado. Reduza o ganho ou aumente a margem do LFE." : "");
            clippingText.setVisibility(clipping ? View.VISIBLE : View.GONE);
        }
        if (usbDetails != null && selectedTab == 2) {
            setTextIfChanged(usbSummary, readableUsbSummary(s));
            if (usbExpanded && android.os.SystemClock.uptimeMillis() >= nextUsbRenderMillis) {
                Object details = s.opt("usbDevices");
                if (details == null) details = s.opt("usb");
                setTextIfChanged(usbDetails, truncate(details == null ? "Sem descritores USB disponíveis." : pretty(details), 18000));
                nextUsbRenderMillis = android.os.SystemClock.uptimeMillis() + 3000;
            }
            hardwareButton.setEnabled(!busy && !running);
            testButton.setEnabled(!busy && !running); fileButton.setEnabled(!busy && !running);
            setTextIfChanged(testButton, busy ? "Teste em execução…" : running ? "Pare o DSP para testar" : "Executar autoteste do DSP");
            exportAudioButton.setEnabled(!busy && bridge.lastOutputUri() != null);
            renderReport(currentReport());
            if (performanceText.getVisibility() == View.VISIBLE) {
                setTextIfChanged(performanceText, running
                        ? "Tempo DSP / duração do bloco: " + fmt(s.optDouble("processingPercent", 0), 1) + "% (pico " + fmt(s.optDouble("processingPeakPercent", 0), 1) + "%)"
                        + "\nInterrupções de saída: " + s.optInt("underruns", 0)
                        + "\nAmostras limitadas: " + s.optLong("clippingSamples", 0)
                        + "\nQuadros capturados / reproduzidos: " + s.optLong("capturedFrames", 0) + " / " + s.optLong("reproducedFrames", 0)
                        + "\nLatência total: ainda não medida."
                        : "Sem sessão ativa. Inicie o DSP para acompanhar o processamento.");
            }
        }
    }

    private void startAudio() {
        main.removeCallbacks(applyEdits);
        applyProfile();
        JSONObject snapshot = bridge.snapshot();
        boolean needsMic = snapshot != null && snapshot.optBoolean("requiresRecordingPermission", false);
        if (needsMic && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
            return;
        }
        actuallyStartAudio();
    }

    private void actuallyStartAudio() {
        notifyWhenRunning = true;
        try { bridge.start(); updateStatus(); }
        catch (RuntimeException error) { showError(error); }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == AUDIO_PERMISSION) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) actuallyStartAudio();
            else toast("A captura de áudio precisa da permissão de microfone do Android.");
        }
    }

    private void runTest(Uri source) {
        if (isBusy()) return;
        JSONObject snapshot = bridge.snapshot();
        if (snapshot != null && snapshot.optBoolean("running", false)) { toast("Pare o DSP para executar testes de arquivo."); return; }
        applyProfile(); testing = true; testReport = "Executando teste no A34…";
        updateStatus();
        AppBridge.Callback result = new AppBridge.Callback() {
            @Override public void onComplete(String report) { showResult(report); }
            @Override public void onError(String message) { showResult("Falha no teste:\n" + message); }
        };
        try {
            if (source == null) bridge.selfTest(result);
            else bridge.testFile(source, result);
        } catch (RuntimeException error) { result.onError(error.getMessage()); }
    }

    private void showResult(String report) {
        main.post(() -> {
            if (isDestroyed()) return;
            testing = false;
            testReport = report == null || report.trim().isEmpty() ? "Teste concluído sem relatório." : report;
            renderReport(testReport);
            updateStatus();
            toast("Teste concluído. Consulte o relatório em Diagnóstico.");
        });
    }

    private void openDocument(int request, String mime) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, request); }
        catch (RuntimeException error) { showError(error); }
    }

    private void createDocument(int request, String mime, String fileName) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime);
        intent.putExtra(Intent.EXTRA_TITLE, fileName);
        try { startActivityForResult(intent, request); }
        catch (RuntimeException error) { showError(error); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == TEST_FILE) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) { }
            showTab(2); runTest(uri); return;
        }
        final JSONObject exportProfile = copy(profile);
        final String exportReport = currentReport();
        final Uri audio = bridge.lastOutputUri();
        documentWorker.submit(() -> {
            try {
                if (request == IMPORT_PROFILE) {
                    JSONObject imported;
                    try (InputStream stream = getContentResolver().openInputStream(uri)) {
                        imported = new JSONObject(readText(stream, 512 * 1024));
                    }
                    // Backend validation is authoritative; no imported settings are applied before it accepts.
                    bridge.applyProfile(imported);
                    main.post(() -> {
                        if (isDestroyed()) return;
                        profile = copy(bridge.loadProfile()); dirty = false; rebuildProfilePage();
                        toast("Perfil importado e salvo neste aparelho.");
                    });
                } else if (request == EXPORT_AUDIO) {
                    if (audio == null) throw new IllegalStateException("Nenhum WAV processado disponível.");
                    try (InputStream input = getContentResolver().openInputStream(audio);
                         OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                        if (input == null || output == null) throw new IllegalStateException("Não foi possível abrir o arquivo.");
                        byte[] buffer = new byte[65536]; int count;
                        while ((count = input.read(buffer)) >= 0) { if (count > 0) output.write(buffer, 0, count); }
                    }
                    main.post(() -> toast("WAV processado salvo."));
                } else {
                    String value = request == EXPORT_PROFILE ? exportProfile.toString(2) : reportJson(exportReport);
                    try (OutputStream stream = getContentResolver().openOutputStream(uri, "wt")) {
                        if (stream == null) throw new IllegalStateException("Não foi possível criar o arquivo.");
                        stream.write(value.getBytes(StandardCharsets.UTF_8));
                    }
                    main.post(() -> toast("Arquivo exportado."));
                }
            } catch (Exception error) { main.post(() -> showError(error)); }
        });
    }

    private void changed() {
        dirty = true;
        editsPending = true;
        if (profileState != null) { profileState.setText("Aplicando alterações…"); profileState.setTextColor(AMBER); }
        syncSharedControls();
        main.removeCallbacks(applyEdits); main.postDelayed(applyEdits, 250);
    }

    private void applyProfile() {
        main.removeCallbacks(applyEdits);
        editsPending = false;
        try {
            bridge.applyProfile(copy(profile)); dirty = false;
            if (profileState != null) { setTextIfChanged(profileState, "Ajustes aplicados e salvos automaticamente."); profileState.setTextColor(TEAL); }
        }
        catch (RuntimeException error) {
            if (profileState != null) { setTextIfChanged(profileState, "Ajuste não aplicado. Confira o modo de entrada e os valores."); profileState.setTextColor(AMBER); }
            showError(error);
        }
    }

    private void put(String key, Object value) {
        try { profile.put(key, value); }
        catch (JSONException error) { throw new IllegalArgumentException(error); }
    }

    private void putArray(String key, int index, Object value) {
        JSONArray array = profile.optJSONArray(key);
        if (array == null) { array = new JSONArray(); put(key, array); }
        try { array.put(index, value); }
        catch (JSONException error) { throw new IllegalArgumentException(error); }
    }

    private double arrayValue(String key, int index, double fallback) {
        JSONArray array = profile.optJSONArray(key);
        return array == null ? fallback : array.optDouble(index, fallback);
    }

    private JSONObject copy(JSONObject value) {
        try { return value == null ? AppBridge.defaultProfile() : new JSONObject(value.toString()); }
        catch (JSONException error) { return AppBridge.defaultProfile(); }
    }

    private boolean isBusy() {
        JSONObject snapshot = bridge.snapshot();
        return testing || snapshot != null && snapshot.optBoolean("busy", false);
    }

    private void chooseMode(String mode, Button nativeButton, Button stereoButton) {
        if (mode.equals(profile.optString("inputMode"))) return;
        JSONObject snapshot = bridge.snapshot();
        if (snapshot != null && snapshot.optBoolean("running", false)) {
            toast("Pare o DSP antes de trocar o modo de entrada."); return;
        }
        put("inputMode", mode); changed(); applyProfile();
        boolean nativeMode = mode.equals("NATIVE_5_1");
        nativeButton.setBackground(shape(nativeMode ? TEAL : RAISED, 12)); nativeButton.setTextColor(nativeMode ? BG : WHITE);
        stereoButton.setBackground(shape(nativeMode ? RAISED : TEAL, 12)); stereoButton.setTextColor(nativeMode ? WHITE : BG);
    }

    private void syncSharedControls() {
        syncingControls = true;
        try {
            float masterDb = linearToDb(profile.optDouble("masterGain", .04));
            for (SliderBinding binding : masterControls) {
                binding.bar.setProgress(Math.round((masterDb - binding.min) / binding.step));
                setTextIfChanged(binding.value, formatValue(masterDb, binding.unit));
            }
            for (ToggleBinding binding : sharedToggles) binding.toggle.setChecked(profile.optBoolean(binding.key, false));
        } finally { syncingControls = false; }
    }

    private static final class SliderBinding {
        final SeekBar bar; final TextView value; final int page; final float min, step; final String unit;
        SliderBinding(SeekBar bar, TextView value, int page, float min, float step, String unit) {
            this.bar = bar; this.value = value; this.page = page; this.min = min; this.step = step; this.unit = unit;
        }
    }

    private static final class ToggleBinding {
        final String key; final Switch toggle; final int page;
        ToggleBinding(String key, Switch toggle, int page) { this.key = key; this.toggle = toggle; this.page = page; }
    }

    private void setTextIfChanged(TextView view, String value) {
        if (view != null && !value.contentEquals(view.getText())) view.setText(value);
    }

    private JSONObject usbState(JSONObject snapshot) {
        JSONObject usb = snapshot.optJSONObject("usbDevices");
        if (usb == null) usb = snapshot.optJSONObject("usb");
        return usb == null ? snapshot : usb;
    }

    private JSONArray audioCapabilities(JSONObject snapshot) {
        JSONArray audio = usbState(snapshot).optJSONArray("androidUsbAudioDevices");
        if (audio == null) audio = snapshot.optJSONArray("androidUsbAudioDevices");
        return audio;
    }

    private int advertisedChannels(JSONObject snapshot, boolean source) {
        JSONArray audio = audioCapabilities(snapshot);
        int maximum = 0;
        boolean sixOutput = false;
        if (audio != null) for (int i = 0; i < audio.length(); i++) {
            JSONObject device = audio.optJSONObject(i);
            if (device == null || !device.optBoolean(source ? "source" : "sink", false)) continue;
            JSONArray counts = device.optJSONArray("channelCounts");
            if (counts != null) for (int c = 0; c < counts.length(); c++) {
                int count = counts.optInt(c, 0); maximum = Math.max(maximum, count); sixOutput |= count == 6;
            }
            if (!source && device.optBoolean("advertisesSixChannels", false)) sixOutput = true;
        }
        return !source && sixOutput ? 6 : maximum;
    }

    private String channelCapabilitySummary(JSONObject snapshot) {
        int input = advertisedChannels(snapshot, true), output = advertisedChannels(snapshot, false);
        return output > 0 && input > 0 ? output + " saídas · " + input + " entradas"
                : output > 0 ? output + " saídas USB" : "Formato USB pendente";
    }

    private String readableUsbSummary(JSONObject snapshot) {
        JSONObject usb = usbState(snapshot);
        JSONArray devices = usb.optJSONArray("usbDevices");
        if (devices == null) devices = snapshot.optJSONArray("usbDevices");
        JSONObject candidate = null;
        if (devices != null) for (int i = 0; i < devices.length(); i++) {
            JSONObject device = devices.optJSONObject(i);
            if (device != null && device.optBoolean("audioCandidate", false)) { candidate = device; break; }
        }
        if (candidate == null && !snapshot.optBoolean("usbAttached", false)) return "Nenhuma interface USB conectada.\nConecte a CM6206 ao A34 com o adaptador OTG.";
        String name = candidate == null ? snapshot.optString("usbSummary", "Interface USB de áudio") : candidate.optString("product", "Interface USB de áudio");
        String id = candidate == null ? "" : candidate.optString("usbId", "");
        boolean permission = candidate == null ? snapshot.optBoolean("hardwareReady", false) : candidate.optBoolean("permission", false);
        return name + (id.isEmpty() ? "" : " · " + id) + "\n"
                + (permission ? "Acesso USB autorizado" : "Toque em Permissão USB para autorizar") + "\n"
                + "Capacidade anunciada: " + channelCapabilitySummary(snapshot);
    }

    private void renderReport(String report) {
        if (reportText == null) return;
        if (!report.equals(renderedReport)) {
            renderedReport = report;
            setTextIfChanged(reportText, reportSummary(report));
        }
        if (reportExpanded && reportDetails != null) setTextIfChanged(reportDetails, truncate(report, 18000));
    }

    private String reportSummary(String report) {
        try {
            JSONObject result = new JSONObject(report);
            String kind = result.optString("kind", "");
            if (kind.equals("app_self_test")) {
                return (result.optBoolean("ok", false) ? "Autoteste aprovado" : "Autoteste com falha")
                        + "\n" + result.optInt("channels", 6) + " canais · " + fmt(result.optInt("sampleRate", 48000) / 1000.0, 0) + " kHz"
                        + "\nAtrasos: " + (result.optBoolean("sampleExact", false) ? "amostras exatas" : "consulte o relatório")
                        + " · perfil: " + (result.optBoolean("profileRoundTrip", false) ? "válido" : "consulte o relatório")
                        + "\nConcluído em " + fmt(result.optDouble("elapsedWallMs", 0), 1) + " ms. Teste em software.";
            }
            if (kind.equals("offline_dsp")) {
                double duration = result.optLong("inputFrames", 0) / Math.max(1.0, result.optDouble("sampleRate", 48000));
                return (result.optBoolean("ok", false) ? "Arquivo processado" : "Processamento com falha")
                        + "\n" + result.optInt("inputChannels", 0) + " canais de entrada → " + result.optInt("channels", 6) + " de saída · " + fmt(duration, 2) + " s"
                        + "\nPior bloco DSP: " + fmt(result.optDouble("maxProcessingBlockMs", 0), 2) + " ms"
                        + "\nAmostras limitadas: " + result.optLong("clippedSamples", 0)
                        + (result.has("decoder") ? "\nAC-3 decodificado pelo Android; fidelidade em validação." : "\nWAV disponível para exportar.");
            }
            if (kind.equals("usb_hardware_snapshot")) {
                return "Diagnóstico da placa concluído\n" + readableUsbSummary(result)
                        + "\nConsulta de descritores e registradores; sem reprodução ou captura de áudio.";
            }
            return (result.optBoolean("ok", false) ? "Teste concluído" : "Relatório disponível")
                    + (kind.isEmpty() ? "" : "\nTipo: " + kind) + "\nAbra o relatório técnico para ver os resultados.";
        } catch (JSONException ignored) { return truncate(report, 1800); }
    }

    private String reportJson(String report) throws JSONException {
        try { return new JSONObject(report).toString(2); }
        catch (JSONException ignored) { return new JSONObject().put("kind", "app_message").put("message", report).toString(2); }
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "\n\nVisualização limitada. Exporte o JSON para consultar o relatório completo.";
    }

    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException ignored) { return "—"; }
    }

    private String currentReport() {
        if (testReport != null) return testReport;
        String report = bridge.lastReport();
        return report == null || report.isEmpty() ? "Nenhum teste executado nesta sessão." : report;
    }

    private String codecSummary() {
        StringBuilder result = new StringBuilder();
        try {
            for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if (codec.isEncoder()) continue;
                for (String type : codec.getSupportedTypes()) {
                    if (type.equalsIgnoreCase("audio/ac3") || type.equalsIgnoreCase("audio/eac3")) {
                        result.append(codec.getName()).append("\n  ").append(type);
                        try {
                            MediaCodecInfo.AudioCapabilities audio = codec.getCapabilitiesForType(type).getAudioCapabilities();
                            if (audio != null) result.append(" · até ").append(audio.getMaxInputChannelCount()).append(" canais de entrada");
                        } catch (RuntimeException ignored) { }
                        result.append("\n\n");
                    }
                }
            }
        } catch (RuntimeException error) { return "Não foi possível consultar os codecs: " + error.getMessage(); }
        return result.length() == 0 ? "Nenhum decodificador AC-3/E-AC-3 anunciado pelo Android." : result.toString().trim();
    }

    private String pretty(Object value) {
        try {
            if (value instanceof JSONObject) return ((JSONObject) value).toString(2);
            if (value instanceof JSONArray) return ((JSONArray) value).toString(2);
        } catch (JSONException ignored) { }
        return String.valueOf(value);
    }

    private String readText(InputStream stream, int maxBytes) throws Exception {
        if (stream == null) throw new IllegalStateException("Arquivo indisponível.");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] block = new byte[4096]; int count;
        while ((count = stream.read(block)) >= 0) {
            if (count == 0) continue;
            if (bytes.size() + count > maxBytes) throw new IllegalArgumentException("O perfil JSON é grande demais.");
            bytes.write(block, 0, count);
        }
        return bytes.toString("UTF-8");
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout;
    }
    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); return layout;
    }
    private LinearLayout card() {
        LinearLayout layout = column(); layout.setPadding(dp(18), dp(18), dp(18), dp(18));
        layout.setBackground(shape(SURFACE, 18)); return layout;
    }
    private void addCard(LinearLayout card) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(12); content.addView(card, p);
    }
    private void heading(LinearLayout parent, String title, String detail) {
        TextView heading = text(title, 18, WHITE);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); parent.addView(heading);
        TextView note = text(detail, 12, MUTED); note.setPadding(0, dp(7), 0, dp(14)); parent.addView(note);
    }
    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setFontFeatureSettings("tnum"); view.setLineSpacing(dp(3), 1); return view;
    }
    private TextView badge(String value, int color) {
        TextView view = text(value, 11, color); view.setSingleLine(true);
        view.setPadding(dp(9), dp(6), dp(9), dp(6)); view.setBackground(shape(RAISED, 7)); return view;
    }
    private Button button(String label, boolean primary) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(13);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(primary ? BG : WHITE); button.setBackground(shape(primary ? TEAL : RAISED, 12));
        button.setBackgroundTintList(null); button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
        button.setPadding(dp(10), 0, dp(10), 0); button.setStateListAnimator(null);
        return button;
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable background = new GradientDrawable(); background.setColor(color); background.setCornerRadius(dp(radius)); return background;
    }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private LinearLayout.LayoutParams fullButton() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48)); p.topMargin = dp(8); return p;
    }
    private EditText numberInput(double value) {
        EditText edit = new EditText(this); edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        edit.setText(fmt(value, 3)); edit.selectAll(); return edit;
    }
    private void toast(String text) { if (!isDestroyed()) Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private void showError(Throwable error) {
        toast(error.getMessage() == null ? "Não foi possível concluir a operação." : error.getMessage());
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private float linearToDb(double value) { return (float) Math.max(-60, value <= 0 ? -60 : 20 * Math.log10(value)); }
    private double dbToLinear(double value) { return Math.pow(10, value / 20); }
    private String formatValue(double value, String unit) { return fmt(value, unit.equals("Hz") ? 0 : unit.equals("ms") ? 3 : 1) + " " + unit; }
    private String fmt(double value, int places) { return String.format(Locale.US, "%1$." + places + "f", value); }

    private final class LevelView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String label;
        private final int color;
        private float level;
        private boolean available;
        private String levelLabel = "—";

        LevelView(String label, int color) {
            super(MainActivity.this); this.label = label; this.color = color;
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            setContentDescription("Nível de saída " + label);
        }
        void setLevel(float value, boolean available) {
            float normalized = Float.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
            if (Float.compare(level, normalized) == 0 && this.available == available) return;
            level = normalized;
            this.available = available; invalidate();
            levelLabel = !available ? "—" : level <= .000032f ? "−∞" : fmt(20 * Math.log10(level), 0);
            setContentDescription("Nível " + label + ": " + (available ? fmt(level <= 0 ? -90 : 20 * Math.log10(level), 1) + " dBFS" : "sem sinal"));
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float center = getWidth() / 2f, barWidth = Math.min(dp(19), getWidth() * .42f);
            float top = dp(27), bottom = getHeight() - dp(28);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity); paint.setColor(color); canvas.drawText(label, center, dp(17), paint);
            paint.setColor(RAISED); canvas.drawRoundRect(center - barWidth / 2, top, center + barWidth / 2, bottom, dp(5), dp(5), paint);
            if (available && level > 0) {
                float db = (float) (20 * Math.log10(level)); float fraction = Math.max(0, Math.min(1, (db + 60) / 60));
                paint.setColor(level >= .99f ? RED : color);
                canvas.drawRoundRect(center - barWidth / 2, bottom - (bottom - top) * fraction, center + barWidth / 2, bottom, dp(5), dp(5), paint);
            }
            paint.setColor(MUTED); paint.setTextSize(10 * getResources().getDisplayMetrics().scaledDensity);
            canvas.drawText(levelLabel, center, getHeight() - dp(5), paint);
        }
    }
}
