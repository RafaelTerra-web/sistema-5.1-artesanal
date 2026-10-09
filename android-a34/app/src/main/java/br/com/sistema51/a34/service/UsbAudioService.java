package br.com.sistema51.a34.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbDevice;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;

import br.com.sistema51.a34.ProfileStore;
import br.com.sistema51.a34.R;
import br.com.sistema51.a34.dsp.AudioProfile;
import br.com.sistema51.a34.dsp.DspEngine;
import br.com.sistema51.a34.usb.UsbAudioController;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Android's routed USB PCM baseline. This is not a raw USB/IEC61937 transport.
 * No start is accepted without a physical USB input and a proven six-channel USB output.
 */
public final class UsbAudioService extends Service {
    public static final String ACTION_START_PCM = "br.com.sistema51.a34.START_PCM";
    public static final String ACTION_STOP_AUDIO = "br.com.sistema51.a34.STOP_AUDIO";
    public static final String EXTRA_USB_DEVICE_ID = "usbDeviceId";
    public static final String OPTICAL_AC3_STATUS = "Captura óptica AC-3 direta pendente validação da interface; PCM USB disponível.";
    public static final int SAMPLE_RATE = 48000;
    public static final int OUTPUT_CHANNELS = 6;
    public static final int BLOCK_FRAMES = 480;

    private static final String CHANNEL_ID = "a34_usb_audio";
    private static final int NOTIFICATION_ID = 51;
    private static final long IO_TIMEOUT_MS = 1500;
    private static final long IO_RETRY_MS = 2;
    private final LocalBinder binder = new LocalBinder();
    private final ExecutorService control = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        }, "A34-USB-Control");
        return thread;
    });
    private final AtomicBoolean startQueued = new AtomicBoolean();
    private final AtomicBoolean stopQueued = new AtomicBoolean();
    private final AtomicLong stopEpoch = new AtomicLong();
    private final Object lifecycleLock = new Object();
    private final Object statusLock = new Object();
    private final double[] meters = new double[OUTPUT_CHANNELS];
    private final double[] peaks = new double[OUTPUT_CHANNELS];
    private UsbAudioController usbController;
    private AudioManager audioManager;
    private volatile AudioRecord audioRecord;
    private volatile AudioTrack audioTrack;
    private volatile Thread audioThread;
    private volatile boolean stopRequested;
    private volatile DspEngine dspEngine;
    private volatile int selectedUsbDeviceId = -1;
    private volatile AudioDeviceInfo selectedInput;
    private volatile AudioDeviceInfo selectedOutput;
    private volatile int inputChannels;
    private volatile int outputChannelIndexMask;
    private volatile int outputChannelMask;
    private volatile boolean running;
    private volatile boolean destroying;
    private volatile boolean starting;
    private volatile boolean stopping;
    private volatile String inputSummary = "Entrada USB ainda não validada.";
    private volatile String outputSummary = "Saída USB 5.1 ainda não validada.";
    private volatile int activeStartId;
    private volatile long audioEpoch;
    private volatile boolean foreground;
    private volatile PowerManager.WakeLock wakeLock;

    // Read or changed under statusLock; the audio worker updates counters at a bounded interval.
    private String state = "waiting_usb";
    private String title = "Aguardando interface USB";
    private String detail = "Conecte a CM6206 ao A34 pelo hub USB-C com alimentação.";
    private String routeMatch = "";
    private long capturedFrames;
    private long reproducedFrames;
    private long clippingSamples;
    private long saturatedSamples;
    private int underruns;
    private double processingPercent;
    private double processingPeakPercent;
    private long startedAtMillis;
    private long inputStalls;
    private long outputStalls;

    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            for (AudioDeviceInfo removed : removedDevices) {
                AudioDeviceInfo input = selectedInput;
                AudioDeviceInfo output = selectedOutput;
                if (running && ((input != null && input.getId() == removed.getId())
                        || (output != null && output.getId() == removed.getId()))) {
                    stopWithStatus("device_removed", "Interface USB desconectada",
                            "O áudio foi interrompido. Reconecte a interface e inicie novamente.");
                    break;
                }
            }
        }
    };

    public final class LocalBinder extends Binder {
        public UsbAudioService getService() { return UsbAudioService.this; }
        public boolean startPcm(AudioProfile profile, int usbDeviceId) {
            return UsbAudioService.this.startPcm(profile, usbDeviceId);
        }
        public void stopAudio() { UsbAudioService.this.stopAudio(); }
        public boolean startOpticalAc3(int usbDeviceId) { return UsbAudioService.this.startOpticalAc3(usbDeviceId); }
        public boolean updateProfile(AudioProfile profile) { return UsbAudioService.this.updateProfile(profile); }
        public JSONObject statusJson() { return UsbAudioService.this.statusJson(); }
        public JSONObject getStatus() { return statusJson(); }
        public boolean isRunning() { return running; }
        /** Includes queued start and asynchronous teardown, for hardware/profile guards. */
        public boolean isBusy() { return running || starting || stopping; }
    }

    @Override public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        usbController = new UsbAudioController(this);
        usbController.setListener(new UsbAudioController.Listener() {
            @Override public void onUsbChanged() { }
            @Override public void onUsbDetached(int deviceId) {
                if (selectedUsbDeviceId == deviceId && running) {
                    stopWithStatus("device_removed", "Interface USB desconectada",
                            "A captura e a reprodução USB foram interrompidas. Reconecte e inicie novamente.");
                }
            }
        });
        audioManager.registerAudioDeviceCallback(deviceCallback, new Handler(Looper.getMainLooper()));
        NotificationManager notifications = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "DSP USB A34", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_START_PCM.equals(intent.getAction())) {
            // Honor the foreground-service deadline even if native teardown is still queued.
            try {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) enterForeground();
            } catch (RuntimeException failure) {
                fail("foreground_failed", "Não foi possível abrir a sessão", safeMessage(failure));
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            if (!queueStart(null, intent.getIntExtra(EXTRA_USB_DEVICE_ID, -1), startId, true)) stopSelf(startId);
        } else if (intent != null && ACTION_STOP_AUDIO.equals(intent.getAction())) {
            stopAudio();
            stopSelf(startId);
        } else {
            stopSelf(startId);
        }
        // No unattended restart, boot capture, or implicit microphone activation.
        return START_NOT_STICKY;
    }

    public boolean startPcm(AudioProfile profile, int usbDeviceId) {
        if (profile == null) {
            fail("profile_required", "Perfil ausente", "Escolha um perfil DSP antes de iniciar.");
            return false;
        }
        return queueStart(profile, usbDeviceId, activeStartId, false);
    }

    /** Returns request acceptance. Native audio creation and teardown never block the UI. */
    private boolean queueStart(AudioProfile profile, int usbDeviceId, int startId, boolean loadSaved) {
        if (destroying) return false;
        if (!startQueued.compareAndSet(false, true)) return true;
        final long expectedEpoch = stopEpoch.get();
        starting = !running;
        if (starting) setStatus("starting", "Preparando PCM USB", "Conferindo a interface, o perfil e as rotas de áudio.");
        try {
            control.execute(() -> {
                try {
                    if (destroying || stopEpoch.get() != expectedEpoch) return;
                    if (!foreground && startId > 0 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) enterForeground();
                    AudioProfile selected = loadSaved ? ProfileStore.load(this) : profile;
                    if (!startPcmOnControl(selected, usbDeviceId, expectedEpoch, startId) && startId > 0) stopSelf(startId);
                } catch (RuntimeException failure) {
                    if (!destroying && stopEpoch.get() == expectedEpoch) {
                        fail("start_failed", "Não foi possível iniciar PCM USB", safeMessage(failure));
                    }
                    if (startId > 0) stopSelf(startId);
                } finally {
                    starting = false;
                    startQueued.set(false);
                    if (!running) { leaveForeground(); releaseWakeLock(); }
                }
            });
            return true;
        } catch (RejectedExecutionException closed) {
            starting = false;
            startQueued.set(false);
            return false;
        }
    }

    private boolean startPcmOnControl(AudioProfile profile, int usbDeviceId, long expectedEpoch, int startId) {
        synchronized (lifecycleLock) {
            if (destroying || stopEpoch.get() != expectedEpoch) return false;
            if (profile == null) {
                fail("profile_required", "Perfil ausente", "Escolha um perfil DSP antes de iniciar.");
                return false;
            }
            if (running) {
                if (selectedUsbDeviceId == usbDeviceId) return updateProfile(profile);
                fail("already_running", "DSP em execução", "Pare a sessão atual antes de trocar a interface USB.");
                return false;
            }
            if (audioThread != null && audioThread.isAlive()) {
                fail("stopping", "Encerrando áudio", "Aguarde o encerramento da sessão antes de iniciar outra.");
                return false;
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                fail("record_permission", "Permissão de captura necessária", "Autorize o microfone no Android para a captura PCM USB.");
                return false;
            }
            UsbDevice usbDevice = usbController.findDevice(usbDeviceId);
            if (usbDevice == null || !UsbAudioController.isAudioCandidate(usbDevice)) {
                fail("waiting_usb", "Aguardando interface USB", "Conecte e selecione a CM6206; nenhum áudio será iniciado sem entrada e saída USB.");
                return false;
            }
            if (!usbController.hasPermission(usbDeviceId)) {
                fail("usb_permission", "Permissão USB necessária", "Use Autorizar USB para o dispositivo selecionado.");
                return false;
            }
            Route route = findRoute(usbDevice);
            if (route == null) return false;
            int channels;
            if (profile.getInputMode() == AudioProfile.InputMode.STEREO_UPMIX) {
                if (knownChannelsExclude(route.input, 2)) {
                    fail("input_channels", "Entrada estéreo indisponível", "O Android não anuncia entrada USB com dois canais nesta interface.");
                    return false;
                }
                channels = 2;
            } else if (supportsChannels(route.input, 6)) {
                channels = 6;
            } else {
                fail("native_input_unavailable", "Entrada PCM 5.1 indisponível",
                        "O Android não anuncia seis canais PCM na entrada USB. Para entrada estéreo, selecione explicitamente o modo Upmix. AC-3 óptico direto continua pendente.");
                return false;
            }
            AudioRecord record = null;
            AudioTrack track = null;
            try {
                AudioFormat.Builder inputFormat = new AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT);
                if (channels == 6) inputFormat.setChannelIndexMask(0x3f);
                else inputFormat.setChannelMask(AudioFormat.CHANNEL_IN_STEREO);
                int minRecordBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                if (minRecordBuffer <= 0) throw new IllegalStateException("48 kHz PCM16 indisponível para captura Android.");
                int recordBuffer = Math.max(BLOCK_FRAMES * channels * 2 * 4, minRecordBuffer * (channels / 2) * 2);
                record = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
                        .setAudioFormat(inputFormat.build()).setBufferSizeInBytes(recordBuffer).build();
                int minTrackBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_5POINT1, AudioFormat.ENCODING_PCM_FLOAT);
                if (minTrackBuffer <= 0) throw new IllegalStateException("48 kHz / seis canais PCM float indisponíveis para reprodução Android.");
                AudioFormat.Builder outputFormat = new AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_FLOAT);
                int indexMask = 0;
                for (int candidate : route.output.getChannelIndexMasks()) if (candidate == 0x3f) indexMask = candidate;
                if (indexMask != 0) outputFormat.setChannelIndexMask(indexMask);
                else outputFormat.setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1);
                track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(outputFormat.build())
                        .setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .setBufferSizeInBytes(Math.max(minTrackBuffer * 2, BLOCK_FRAMES * OUTPUT_CHANNELS * 4 * 4)).build();
                if (record.getState() != AudioRecord.STATE_INITIALIZED || track.getState() != AudioTrack.STATE_INITIALIZED
                        || record.getChannelCount() != channels || track.getChannelCount() != OUTPUT_CHANNELS) {
                    throw new IllegalStateException("O Android recusou o formato PCM solicitado.");
                }
                if (!record.setPreferredDevice(route.input) || !track.setPreferredDevice(route.output)) {
                    throw new IllegalStateException("O Android recusou o roteamento para a interface USB selecionada.");
                }
                DspEngine engine = new DspEngine(BLOCK_FRAMES);
                engine.setProfile(profile);
                if (destroying || stopEpoch.get() != expectedEpoch) {
                    releaseAudio(record, track);
                    return false;
                }
                selectedUsbDeviceId = usbDeviceId;
                selectedInput = route.input;
                selectedOutput = route.output;
                inputChannels = channels;
                outputChannelIndexMask = track.getFormat().getChannelIndexMask();
                outputChannelMask = track.getFormat().getChannelMask();
                inputSummary = channels + " canais PCM • " + route.input.getProductName() + " • " + route.input.getAddress();
                outputSummary = "6 canais PCM • " + route.output.getProductName() + " • " + route.output.getAddress();
                activeStartId = startId;
                audioEpoch = expectedEpoch;
                dspEngine = engine;
                audioRecord = record;
                audioTrack = track;
                stopRequested = false;
                synchronized (statusLock) {
                    routeMatch = route.match;
                    capturedFrames = reproducedFrames = clippingSamples = saturatedSamples = 0;
                    underruns = 0;
                    inputStalls = outputStalls = 0;
                    processingPercent = processingPeakPercent = 0;
                    startedAtMillis = System.currentTimeMillis();
                    for (int channel = 0; channel < OUTPUT_CHANNELS; channel++) { meters[channel] = 0; peaks[channel] = 0; }
                }
                enterForeground();
                PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":UsbPcm");
                wakeLock.setReferenceCounted(false);
                // Renewed on active audio blocks; bounded hold if an unforeseen worker failure occurs.
                wakeLock.acquire(10 * 60 * 1000L);
                running = true;
                stopping = false;
                setStatus("starting", "Iniciando PCM USB", "Validando o roteamento real da entrada e da saída USB.");
                audioThread = new Thread(this::runAudio, "A34-USB-DSP");
                audioThread.start();
                return true;
            } catch (RuntimeException failure) {
                releaseAudio(record, track);
                audioRecord = null;
                audioTrack = null;
                dspEngine = null;
                running = false;
                selectedInput = selectedOutput = null;
                inputSummary = "Entrada USB ainda não validada.";
                outputSummary = "Saída USB 5.1 ainda não validada.";
                leaveForeground();
                releaseWakeLock();
                fail("start_failed", "Não foi possível iniciar PCM USB", failure.getClass().getSimpleName() + ": " + safeMessage(failure));
                return false;
            }
        }
    }

    public boolean updateProfile(AudioProfile profile) {
        DspEngine engine = dspEngine;
        if (engine == null || profile == null) return false;
        if ((inputChannels == 2 && profile.getInputMode() != AudioProfile.InputMode.STEREO_UPMIX)
                || (inputChannels == 6 && profile.getInputMode() != AudioProfile.InputMode.NATIVE_5_1)) {
            setStatus("restart_required", "Troca de entrada exige reinício", "Pare o DSP e inicie novamente após mudar entre entrada nativa e Upmix.");
            return false;
        }
        engine.setProfile(profile);
        return true;
    }

    /** Kept separate from PCM: AudioRecord is not evidence of a raw AC-3 USB path. */
    public boolean startOpticalAc3(int usbDeviceId) {
        if (running) {
            setStatus("running", "DSP PCM USB em execução", "Pare a sessão PCM antes de testar outro transporte. " + OPTICAL_AC3_STATUS);
            return false;
        }
        fail("optical_ac3_unvalidated", "Captura óptica AC-3 pendente", OPTICAL_AC3_STATUS);
        return false;
    }

    public void stopAudio() {
        stopWithStatus("stopped", "DSP parado", "A sessão PCM foi encerrada. Inicie manualmente quando desejar.");
    }

    private void stopWithStatus(String state, String title, String detail) {
        // Publish the gate immediately. The control worker handles native calls and the join.
        stopEpoch.incrementAndGet();
        stopRequested = true;
        stopping = running || starting;
        setStatus(state, title, detail);
        if (!stopQueued.compareAndSet(false, true)) return;
        final int stoppedStartId = activeStartId;
        try {
            control.execute(() -> {
                try { stopOnControl(stoppedStartId); }
                finally { stopQueued.set(false); }
            });
        } catch (RejectedExecutionException closed) {
            stopQueued.set(false);
        }
    }

    private void stopOnControl(int stoppedStartId) {
        synchronized (lifecycleLock) {
            stopRequested = true;
            AudioRecord record = audioRecord;
            AudioTrack track = audioTrack;
            if (record != null) {
                try { record.stop(); } catch (IllegalStateException ignored) { }
            }
            if (track != null) {
                try { track.pause(); } catch (IllegalStateException ignored) { }
            }
        }
        Thread worker = audioThread;
        if (worker != null && worker != Thread.currentThread()) {
            try { worker.join(600); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        if (worker == null || !worker.isAlive()) {
            running = false;
            stopping = false;
            leaveForeground();
            releaseWakeLock();
        }
        if (stoppedStartId > 0) stopSelf(stoppedStartId);
    }

    private void runAudio() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        final AudioRecord record = audioRecord;
        final AudioTrack track = audioTrack;
        final DspEngine engine = dspEngine;
        final int channels = inputChannels;
        final long sessionEpoch = audioEpoch;
        final short[] capture = new short[BLOCK_FRAMES * channels];
        final float[] input = new float[capture.length];
        final float[] output = new float[BLOCK_FRAMES * OUTPUT_CHANNELS];
        final double[] sumSquares = new double[OUTPUT_CHANNELS];
        final double[] intervalPeaks = new double[OUTPUT_CHANNELS];
        long readFrames = 0;
        long writtenFrames = 0;
        long clipped = 0;
        long saturated = 0;
        long intervalSamples = 0;
        long processNanos = 0;
        long processFrames = 0;
        long peakProcessNanos = 0;
        long readStalls = 0;
        long writeStalls = 0;
        final int sessionStartId = activeStartId;
        long lastSnapshot = android.os.SystemClock.elapsedRealtime();
        long lastWakeRenewal = lastSnapshot;
        final long started = lastSnapshot;
        boolean routeConfirmed = false;
        try {
            if (sessionStopping(sessionEpoch)) return;
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("Captura USB não iniciou.");
            // Prime with silence only. Real samples remain gated on both confirmed USB routes.
            track.write(output, 0, output.length, AudioTrack.WRITE_NON_BLOCKING);
            track.play();
            while (!sessionStopping(sessionEpoch)) {
                int filled = 0;
                boolean readWaitCounted = false;
                long readDeadline = android.os.SystemClock.elapsedRealtime() + IO_TIMEOUT_MS;
                while (filled < capture.length && !sessionStopping(sessionEpoch)) {
                    int count = record.read(capture, filled, capture.length - filled, AudioRecord.READ_NON_BLOCKING);
                    if (count < 0) throw new IllegalStateException("Falha de captura PCM (" + count + ").");
                    if (count == 0) {
                        if (!readWaitCounted) { readStalls++; readWaitCounted = true; }
                        if (android.os.SystemClock.elapsedRealtime() >= readDeadline) throw new IllegalStateException("A entrada USB não forneceu um bloco PCM por 1,5 s.");
                        android.os.SystemClock.sleep(IO_RETRY_MS);
                        continue;
                    }
                    filled += count;
                }
                if (sessionStopping(sessionEpoch)) break;
                if (filled % channels != 0) throw new IllegalStateException("Captura retornou um quadro PCM incompleto.");
                int frames = filled / channels;
                readFrames += frames;
                AudioDeviceInfo actualInput = record.getRoutedDevice();
                AudioDeviceInfo actualOutput = track.getRoutedDevice();
                if ((actualInput != null && !sameDevice(actualInput, selectedInput))
                        || (actualOutput != null && !sameDevice(actualOutput, selectedOutput))) {
                    throw new IllegalStateException("O Android alterou a rota USB. Sessão interrompida para evitar saída em outro dispositivo.");
                }
                long routeNow = android.os.SystemClock.elapsedRealtime();
                if (actualInput == null || actualOutput == null) {
                    if (routeConfirmed) throw new IllegalStateException("O Android deixou de confirmar uma das rotas USB.");
                    if (routeNow - started > 1500) throw new IllegalStateException("O Android não confirmou as duas rotas USB.");
                    // Until route confirmation, output contains only the initial zero block.
                    writeStartupSilence(track, output, sessionEpoch);
                    continue;
                }
                if (!routeConfirmed && !sessionStopping(sessionEpoch)) {
                    setStatus("running", "DSP PCM USB em execução", channels == 6
                            ? "PCM 5.1 nativo • 48 kHz • blocos de 10 ms. Latência e relógios ainda precisam de medição na interface real."
                            : "Entrada PCM estéreo com Upmix explícito para seis canais • 48 kHz. A captura óptica AC-3 não está validada.");
                    routeConfirmed = true;
                }
                for (int index = 0; index < filled; index++) input[index] = capture[index] / 32768.0f;
                long beforeDsp = System.nanoTime();
                engine.process(input, channels, output, frames);
                clipped = engine.getClippedSamples();
                long elapsedDsp = System.nanoTime() - beforeDsp;
                processNanos += elapsedDsp;
                processFrames += frames;
                peakProcessNanos = Math.max(peakProcessNanos, elapsedDsp);
                int samples = frames * OUTPUT_CHANNELS;
                for (int frame = 0; frame < frames; frame++) {
                    int base = frame * OUTPUT_CHANNELS;
                    for (int channel = 0; channel < OUTPUT_CHANNELS; channel++) {
                        double value = output[base + channel];
                        double peak = Math.abs(value);
                        sumSquares[channel] += value * value;
                        if (peak > intervalPeaks[channel]) intervalPeaks[channel] = peak;
                        if (peak >= 0.9999) saturated++;
                    }
                }
                intervalSamples += frames;
                int written = 0;
                boolean writeWaitCounted = false;
                long writeDeadline = android.os.SystemClock.elapsedRealtime() + IO_TIMEOUT_MS;
                while (written < samples && !sessionStopping(sessionEpoch)) {
                    if (!sameDevice(track.getRoutedDevice(), selectedOutput)) {
                        throw new IllegalStateException("A saída deixou de usar a interface USB selecionada.");
                    }
                    int count = track.write(output, written, samples - written, AudioTrack.WRITE_NON_BLOCKING);
                    if (count < 0) throw new IllegalStateException("Falha de reprodução PCM (" + count + ").");
                    if (count == 0) {
                        if (!writeWaitCounted) { writeStalls++; writeWaitCounted = true; }
                        if (android.os.SystemClock.elapsedRealtime() >= writeDeadline) throw new IllegalStateException("A saída USB não aceitou um bloco PCM por 1,5 s.");
                        android.os.SystemClock.sleep(IO_RETRY_MS);
                        continue;
                    }
                    written += count;
                }
                writtenFrames += written / OUTPUT_CHANNELS;
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastSnapshot >= 500) {
                    synchronized (statusLock) {
                        capturedFrames = readFrames;
                        reproducedFrames = writtenFrames;
                        clippingSamples = clipped;
                        saturatedSamples = saturated;
                        inputStalls = readStalls;
                        outputStalls = writeStalls;
                        underruns = track.getUnderrunCount();
                        processingPercent = processFrames == 0 ? 0 : processNanos * SAMPLE_RATE / (processFrames * 1_000_000_000.0) * 100.0;
                        processingPeakPercent = peakProcessNanos * SAMPLE_RATE / (BLOCK_FRAMES * 1_000_000_000.0) * 100.0;
                        for (int channel = 0; channel < OUTPUT_CHANNELS; channel++) {
                            meters[channel] = intervalSamples == 0 ? 0 : Math.sqrt(sumSquares[channel] / intervalSamples);
                            peaks[channel] = intervalPeaks[channel];
                            sumSquares[channel] = 0;
                            intervalPeaks[channel] = 0;
                        }
                    }
                    intervalSamples = processNanos = processFrames = 0;
                    lastSnapshot = now;
                }
                if (now - lastWakeRenewal >= 5 * 60 * 1000L) {
                    PowerManager.WakeLock activeLock = wakeLock;
                    if (activeLock != null) activeLock.acquire(10 * 60 * 1000L);
                    lastWakeRenewal = now;
                }
            }
        } catch (RuntimeException failure) {
            if (!sessionStopping(sessionEpoch)) setStatus("audio_failed", "PCM USB interrompido", safeMessage(failure));
        } finally {
            int finalUnderruns;
            try { finalUnderruns = track.getUnderrunCount(); }
            catch (RuntimeException ignored) { synchronized (statusLock) { finalUnderruns = underruns; } }
            synchronized (statusLock) {
                capturedFrames = readFrames;
                reproducedFrames = writtenFrames;
                clippingSamples = clipped;
                saturatedSamples = saturated;
                inputStalls = readStalls;
                outputStalls = writeStalls;
                underruns = finalUnderruns;
                if (processFrames > 0) processingPercent = processNanos * SAMPLE_RATE / (processFrames * 1_000_000_000.0) * 100.0;
                processingPeakPercent = peakProcessNanos * SAMPLE_RATE / (BLOCK_FRAMES * 1_000_000_000.0) * 100.0;
                for (int channel = 0; channel < OUTPUT_CHANNELS; channel++) { meters[channel] = 0; peaks[channel] = 0; }
            }
            synchronized (lifecycleLock) {
                // Native stop/release is serialized with the control worker, never the UI.
                releaseAudio(record, track);
                if (audioRecord == record) audioRecord = null;
                if (audioTrack == track) audioTrack = null;
                dspEngine = null;
                running = false;
                stopping = false;
                selectedInput = selectedOutput = null;
                inputSummary = "Entrada USB ainda não validada.";
                outputSummary = "Saída USB 5.1 ainda não validada.";
                audioThread = null;
                leaveForeground();
                releaseWakeLock();
            }
            if (sessionStartId > 0) stopSelf(sessionStartId);
        }
    }

    private boolean sessionStopping(long epoch) { return stopRequested || stopEpoch.get() != epoch; }

    private void writeStartupSilence(AudioTrack track, float[] silence, long epoch) {
        int written = 0;
        long deadline = android.os.SystemClock.elapsedRealtime() + IO_TIMEOUT_MS;
        while (written < silence.length && !sessionStopping(epoch)) {
            int count = track.write(silence, written, silence.length - written, AudioTrack.WRITE_NON_BLOCKING);
            if (count < 0) throw new IllegalStateException("Falha ao preparar saída PCM (" + count + ").");
            if (count == 0) {
                if (android.os.SystemClock.elapsedRealtime() >= deadline) throw new IllegalStateException("A saída USB não aceitou silêncio de preparação por 1,5 s.");
                android.os.SystemClock.sleep(IO_RETRY_MS);
            } else written += count;
        }
    }

    public JSONObject statusJson() {
        JSONObject result = new JSONObject();
        try {
            synchronized (statusLock) {
                result.put("state", state).put("title", title).put("detail", detail).put("running", running);
                result.put("starting", starting).put("stopping", stopping);
                result.put("sampleRate", SAMPLE_RATE).put("channels", OUTPUT_CHANNELS).put("inputChannels", inputChannels);
                result.put("blockFrames", BLOCK_FRAMES).put("usbDeviceId", selectedUsbDeviceId).put("routeMatch", routeMatch);
                result.put("outputChannelIndexMask", outputChannelIndexMask).put("outputChannelMask", outputChannelMask);
                result.put("capturedFrames", capturedFrames).put("reproducedFrames", reproducedFrames).put("underruns", underruns);
                result.put("clipping", clippingSamples > 0).put("clippingSamples", clippingSamples);
                result.put("saturatedSamples", saturatedSamples);
                result.put("inputWaitBlocks", inputStalls).put("outputWaitBlocks", outputStalls);
                result.put("ioWaitMeaning", "Blocos que aguardaram dados/espaço nas chamadas não bloqueantes; espera normal não é underrun.");
                result.put("clippingMeaning", "Quantidade de amostras DSP limitadas por exceder ±1; não mede saturação anterior à captura USB.");
                result.put("processingPercent", processingPercent).put("processingPeakPercent", processingPeakPercent);
                result.put("processingMeaning", "Tempo do DSP como fração dos 10 ms de áudio; não mede CPU total, I/O ou latência. Pico é o máximo da sessão.");
                result.put("startedAtMillis", startedAtMillis);
                JSONArray rms = new JSONArray();
                JSONArray peakValues = new JSONArray();
                for (int channel = 0; channel < OUTPUT_CHANNELS; channel++) { rms.put(meters[channel]); peakValues.put(peaks[channel]); }
                result.put("meters", rms).put("peaks", peakValues);
            }
            result.put("inputSummary", inputSummary);
            result.put("outputSummary", outputSummary);
            result.put("opticalAc3", OPTICAL_AC3_STATUS);
            result.put("transport", "AudioRecord PCM16 USB → DSP → AudioTrack PCM_FLOAT USB 5.1");
            result.put("clockCalibration", "Pendente: sem resampling adaptativo ou compensação de relógios independentes.");
            result.put("bitPerfect", "Não comprovado: Android pode aplicar conversão e processamento no caminho PCM.");
            result.put("latencyMeasured", false);
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        return result;
    }

    private Route findRoute(UsbDevice selected) {
        AudioDeviceInfo[] androidDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS | AudioManager.GET_DEVICES_OUTPUTS);
        Map<String, List<AudioDeviceInfo>> groups = new HashMap<>();
        for (AudioDeviceInfo device : androidDevices) {
            if (!isUsb(device) || device.getAddress() == null || device.getAddress().isEmpty()) continue;
            String address = physicalAddress(device.getAddress());
            List<AudioDeviceInfo> group = groups.get(address);
            if (group == null) { group = new ArrayList<>(); groups.put(address, group); }
            group.add(device);
        }
        List<List<AudioDeviceInfo>> matches = new ArrayList<>();
        for (List<AudioDeviceInfo> group : groups.values()) {
            for (AudioDeviceInfo device : group) {
                String product = selected.getProductName();
                if (device.getAddress().equals(selected.getDeviceName())
                        || (product != null && product.equalsIgnoreCase(device.getProductName().toString()))) {
                    matches.add(group);
                    break;
                }
            }
        }
        String matchReason = "Correspondência por endereço/nome USB e placa ALSA.";
        if (matches.isEmpty()) {
            int physicalCandidates = 0;
            for (UsbDevice candidate : usbController.listDevices()) if (UsbAudioController.isAudioCandidate(candidate)) physicalCandidates++;
            if (physicalCandidates == 1 && groups.size() == 1) {
                matches.add(groups.values().iterator().next());
                matchReason = "Correspondência pela única interface física USB de áudio e única placa Android USB; confirmar canais no teste real.";
            }
        }
        if (matches.size() != 1) {
            fail("route_ambiguous", "Rota USB não identificada", "O Android não expôs uma rota única para a interface selecionada. Conecte apenas uma interface USB de áudio para esta primeira validação.");
            return null;
        }
        AudioDeviceInfo input = null;
        AudioDeviceInfo output = null;
        for (AudioDeviceInfo device : matches.get(0)) {
            if (device.isSource() && (input == null || supportsChannels(device, 6))) input = device;
            if (device.isSink() && supportsChannels(device, OUTPUT_CHANNELS)) output = device;
        }
        if (input == null) {
            fail("usb_input_missing", "Entrada USB ausente", "A interface USB selecionada não possui entrada PCM exposta pelo Android.");
            return null;
        }
        if (output == null) {
            fail("usb_output_5_1_missing", "Saída USB 5.1 indisponível", "O Android não anuncia seis canais de saída na interface selecionada. A sessão não será iniciada.");
            return null;
        }
        return new Route(input, output, matchReason);
    }

    private static String physicalAddress(String address) {
        // Input and output can use different PCM subdevices on the same USB ALSA card.
        int start = address.indexOf("card=");
        if (start >= 0) {
            int end = address.indexOf(';', start);
            return end < 0 ? address.substring(start) : address.substring(start, end);
        }
        return address;
    }

    private static boolean supportsChannels(AudioDeviceInfo device, int wanted) {
        for (int count : device.getChannelCounts()) if (count == wanted) return true;
        for (int mask : device.getChannelMasks()) if (Integer.bitCount(mask) == wanted) return true;
        for (int mask : device.getChannelIndexMasks()) if (Integer.bitCount(mask) == wanted) return true;
        return false;
    }

    private static boolean knownChannelsExclude(AudioDeviceInfo device, int wanted) {
        return device.getChannelCounts().length > 0 && !supportsChannels(device, wanted);
    }

    private static boolean isUsb(AudioDeviceInfo device) {
        return device.getType() == AudioDeviceInfo.TYPE_USB_DEVICE || device.getType() == AudioDeviceInfo.TYPE_USB_HEADSET;
    }

    private static boolean sameDevice(AudioDeviceInfo actual, AudioDeviceInfo expected) {
        return actual != null && expected != null && isUsb(actual) && actual.getId() == expected.getId();
    }

    private static final class Route {
        final AudioDeviceInfo input;
        final AudioDeviceInfo output;
        final String match;
        Route(AudioDeviceInfo input, AudioDeviceInfo output, String match) { this.input = input; this.output = output; this.match = match; }
    }

    private void enterForeground() {
        Intent stop = new Intent(this, UsbAudioService.class).setAction(ACTION_STOP_AUDIO);
        PendingIntent stopIntent = PendingIntent.getService(this, 51, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID).setContentTitle("Sistema 5.1 • DSP USB")
                .setContentText("Captura PCM e processamento em andamento.").setSmallIcon(R.drawable.ic_notification)
                .setCategory(Notification.CATEGORY_SERVICE).setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "Parar DSP", stopIntent).build());
        if (launch != null) builder.setContentIntent(PendingIntent.getActivity(this, 52, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, builder.build());
        }
        foreground = true;
    }

    private void leaveForeground() {
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
    }

    private void releaseWakeLock() {
        PowerManager.WakeLock held = wakeLock;
        wakeLock = null;
        if (held != null && held.isHeld()) held.release();
    }

    /** A disconnect must not prevent the remaining resources and wake lock from being released. */
    private static void releaseAudio(AudioRecord record, AudioTrack track) {
        if (record != null) {
            try { record.stop(); } catch (RuntimeException ignored) { }
            try { record.release(); } catch (RuntimeException ignored) { }
        }
        if (track != null) {
            try { track.stop(); } catch (RuntimeException ignored) { }
            try { track.release(); } catch (RuntimeException ignored) { }
        }
    }

    private void fail(String state, String title, String detail) { setStatus(state, title, detail); }

    private void setStatus(String state, String title, String detail) {
        synchronized (statusLock) { this.state = state; this.title = title; this.detail = detail; }
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message;
    }

    @Override public void onDestroy() {
        destroying = true;
        synchronized (statusLock) { stopWithStatus(state, title, detail); }
        audioManager.unregisterAudioDeviceCallback(deviceCallback);
        usbController.close();
        // The queued stop may finish after onDestroy; the audio worker owns final resource release.
        control.shutdown();
        super.onDestroy();
    }
}
