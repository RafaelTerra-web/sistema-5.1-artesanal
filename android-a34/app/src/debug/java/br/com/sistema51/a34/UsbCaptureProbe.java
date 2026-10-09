package br.com.sistema51.a34;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Debug-only, three-second USB transport sample. No output or HID register writes.
 * Android UNPROCESSED is a request, not proof of an unmodified/bit-perfect optical path:
 * https://developer.android.com/reference/android/media/AudioRecord
 * Preamble bytes only indicate candidates; parse Pc/Pd and payload on the PC afterwards:
 * https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/spdifdec.c
 * Captured media is private test data and must not be redistributed or committed.
 */
public final class UsbCaptureProbe {
    private static final int RATE = 48000, CHANNELS = 2, BLOCK_FRAMES = 480;
    private static final int MAX_FRAMES = RATE * 3;
    private UsbCaptureProbe() { }

    public static JSONObject capture(Context context, File directory) throws Exception {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw new IllegalStateException("Autorize captura USB antes do teste.");
        String privateRoot = context.getFilesDir().getCanonicalPath();
        String outputRoot = directory.getCanonicalPath();
        if (!outputRoot.equals(privateRoot) && !outputRoot.startsWith(privateRoot + File.separator))
            throw new IOException("A captura de teste deve permanecer no diretório privado do aplicativo.");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Diretório de teste indisponível.");
        UsbManager usb = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        UsbDevice cm = null;
        int usbAudioDevices = 0;
        for (UsbDevice device : usb.getDeviceList().values()) {
            boolean audio = false;
            for (int i = 0; i < device.getInterfaceCount(); i++)
                if (device.getInterface(i).getInterfaceClass() == 1) audio = true;
            if (audio) usbAudioDevices++;
            if (device.getVendorId() == 0x0d8c && device.getProductId() == 0x0102) {
                if (cm != null) throw new IllegalStateException("Mais de uma CM6206; rota ambígua.");
                cm = device;
            }
        }
        if (cm == null || !usb.hasPermission(cm)) throw new IllegalStateException("CM6206 ausente ou sem permissão USB.");
        if (usbAudioDevices != 1) throw new IllegalStateException("A captura exige uma única interface de áudio USB.");
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo selected = null;
        for (AudioDeviceInfo candidate : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (candidate.getType() != AudioDeviceInfo.TYPE_USB_DEVICE && candidate.getType() != AudioDeviceInfo.TYPE_USB_HEADSET) continue;
            if (selected != null) throw new IllegalStateException("Mais de uma entrada de áudio USB; rota ambígua.");
            selected = candidate;
        }
        if (selected == null) throw new IllegalStateException("Entrada USB não anunciada pelo Android.");
        int[] channels = selected.getChannelCounts();
        boolean stereo = channels.length == 0;
        for (int n : channels) if (n == 2) stereo = true;
        if (!stereo) throw new IllegalStateException("A entrada USB não anuncia dois canais.");

        File pcm = new File(directory, "capture.pcm");
        AudioRecord record = null;
        boolean complete = false, fileOpened = false;
        long startWallMs = System.currentTimeMillis(), begun = SystemClock.elapsedRealtime();
        long frames = 0;
        int calls = 0, emptyReads = 0;
        int[] minimum = {32767, 32767}, maximum = {-32768, -32768}, peak = {0, 0};
        double[] sumSquares = {0, 0};
        int littleMarkers = 0, bigMarkers = 0, byteWindow = 0, byteWindowCount = 0;
        short[] samples = new short[BLOCK_FRAMES * CHANNELS];
        byte[] bytes = new byte[samples.length * 2];
        try {
            int minBytes = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBytes <= 0) throw new IllegalStateException("Captura estéreo PCM16 a 48 kHz indisponível.");
            record = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
                    .setBufferSizeInBytes(Math.max(minBytes * 2, bytes.length * 4)).build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED || record.getChannelCount() != CHANNELS
                    || record.getSampleRate() != RATE || record.getAudioFormat() != AudioFormat.ENCODING_PCM_16BIT)
                throw new IllegalStateException("Android recusou o formato solicitado.");
            if (!record.setPreferredDevice(selected)) throw new IllegalStateException("Rota preferencial USB recusada.");
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("Captura não iniciou.");
            // Retain no samples until the actual USB route is observed. Never accept phone-mic fallback.
            long routeDeadline = SystemClock.elapsedRealtime() + 500;
            while (record.getRoutedDevice() == null && SystemClock.elapsedRealtime() < routeDeadline) {
                checkInterrupted(); SystemClock.sleep(2);
            }
            requireRoute(record, selected);
            // Drain startup data before retention; all reads are guarded on both sides.
            long drainDeadline = SystemClock.elapsedRealtime() + 250;
            boolean drained = false;
            while (SystemClock.elapsedRealtime() < drainDeadline) {
                checkInterrupted(); requireRoute(record, selected);
                int n = record.read(samples, 0, samples.length, AudioRecord.READ_NON_BLOCKING);
                requireRoute(record, selected); requireRead(n);
                if (n == 0) { drained = true; break; }
            }
            if (!drained) throw new IllegalStateException("Buffer inicial não esvaziou dentro do limite.");
            long captureStarted = SystemClock.elapsedRealtime(), deadline = captureStarted + 3000;
            try (FileOutputStream out = new FileOutputStream(pcm, false)) {
                fileOpened = true;
                while (frames < MAX_FRAMES && SystemClock.elapsedRealtime() < deadline) {
                    checkInterrupted(); requireRoute(record, selected);
                    int wanted = (int) Math.min(samples.length, (MAX_FRAMES - frames) * CHANNELS);
                    int n = record.read(samples, 0, wanted, AudioRecord.READ_NON_BLOCKING);
                    requireRoute(record, selected); requireRead(n); calls++;
                    if (n == 0) { emptyReads++; SystemClock.sleep(2); continue; }
                    for (int i = 0; i < n; i++) {
                        int channel = i % CHANNELS, value = samples[i];
                        minimum[channel] = Math.min(minimum[channel], value);
                        maximum[channel] = Math.max(maximum[channel], value);
                        peak[channel] = Math.max(peak[channel], Math.abs(value));
                        sumSquares[channel] += (double) value * value;
                        bytes[i * 2] = (byte) value; bytes[i * 2 + 1] = (byte) (value >>> 8);
                    }
                    for (int i = 0; i < n * 2; i++) {
                        byteWindow = (byteWindow << 8) | (bytes[i] & 255);
                        if (byteWindowCount < 4) byteWindowCount++;
                        if (byteWindowCount == 4) {
                            if (byteWindow == 0x72f81f4e) littleMarkers++;
                            if (byteWindow == 0xf8724e1f) bigMarkers++;
                        }
                    }
                    out.write(bytes, 0, n * 2); frames += n / CHANNELS;
                }
                requireRoute(record, selected);
            }
            if (frames == 0) throw new IllegalStateException("A captura USB não entregou amostras.");
            JSONArray statistics = new JSONArray();
            for (int channel = 0; channel < CHANNELS; channel++) statistics.put(new JSONObject()
                    .put("channelIndex", channel).put("min", minimum[channel]).put("max", maximum[channel])
                    .put("peakInteger", peak[channel]).put("rmsInteger", Math.sqrt(sumSquares[channel] / frames)));
            JSONObject result = new JSONObject().put("ok", true).put("kind", "usb_capture_transport_probe")
                    .put("startedAtUnixMs", startWallMs).put("elapsedMs", SystemClock.elapsedRealtime() - begun)
                    .put("captureElapsedMs", SystemClock.elapsedRealtime() - captureStarted).put("limitFrames", MAX_FRAMES)
                    .put("sampleRate", RATE).put("channels", CHANNELS).put("encoding", "signed_pcm16_little_endian")
                    .put("requestedAudioSource", "UNPROCESSED").put("actualAudioSource", record.getAudioSource())
                    .put("actualChannelMask", record.getFormat().getChannelMask()).put("actualRouteId", record.getRoutedDevice().getId())
                    .put("selectedId", selected.getId())
                    .put("selectedType", selected.getType()).put("selectedAddress", selected.getAddress())
                    .put("selectedProduct", selected.getProductName()).put("routeVerified", true)
                    .put("routeMatch", "unique_physical_usb_audio_device_and_unique_framework_usb_input")
                    .put("frames", frames).put("rawBytes", frames * CHANNELS * 2).put("filename", pcm.getName())
                    .put("readCalls", calls).put("emptyReads", emptyReads).put("pcmStatistics", statistics)
                    .put("preamble72f81f4eCount", littleMarkers).put("preamblef8724e1fCount", bigMarkers)
                    .put("opticalSourceValidated", false).put("ac3Validated", false).put("bitPerfectValidated", false)
                    .put("privateCapture", true).put("scope", "USB-routed raw AudioRecord samples only. Optical source, unchanged payload, codec, and AC-3 CRC require separate validation; marker counts are not valid AC-3 evidence.");
            complete = true;
            return result;
        } finally {
            try {
                if (record != null) {
                    try { record.stop(); } catch (RuntimeException ignored) { }
                    record.release();
                }
            } finally {
                if (fileOpened && !complete && pcm.exists() && !pcm.delete())
                    throw new IOException("Captura abortada; não foi possível remover arquivo parcial privado.");
            }
        }
    }

    private static void requireRoute(AudioRecord record, AudioDeviceInfo selected) {
        AudioDeviceInfo actual = record.getRoutedDevice();
        if (actual == null || actual.getId() != selected.getId() || actual.getType() != selected.getType()
                || !actual.getAddress().equals(selected.getAddress()))
            throw new IllegalStateException("Rota de entrada USB não confirmada ou alterada; captura abortada.");
    }
    private static void requireRead(int count) {
        if (count < 0 || count % CHANNELS != 0) throw new IllegalStateException("Leitura PCM estéreo inválida: " + count);
    }
    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Captura cancelada.");
    }
}
