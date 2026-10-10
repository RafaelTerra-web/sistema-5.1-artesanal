package br.com.sistema51.a34.usb;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Exclusive, continuous transport for the physically measured CM6206 topology.
 * Captured bytes remain opaque IEC61937 carrier. Only decoded six-channel PCM16
 * explicitly written by the caller can reach output; the initial native gate is muted.
 */
public final class Cm6206DuplexTransport implements EncodedUsbAudioTransport {
    static { System.loadLibrary("sistema51_usb"); }
    private final Object lifecycle = new Object();
    private final File journal;
    private final File diagnosticJournal;
    private UsbDeviceConnection connection;
    private UsbInterface controlInterface, inputInterface, outputInterface;
    private boolean controlClaimed, inputClaimed, outputClaimed, sourceTouched;
    private boolean closing, closed;
    private int originalPin = -1, activeCalls;
    private long handle;
    private volatile boolean muted = true;
    private volatile JSONObject lastStats = new JSONObject();
    private volatile String restorationError = "";

    public Cm6206DuplexTransport(Context context) {
        File hardware = new File(context.getApplicationContext().getFilesDir(), "hardware");
        journal = new File(hardware, "continuous-optical-journal.json");
        diagnosticJournal = new File(hardware, "raw-capture-journal.json");
    }

    @Override public Validation validation() { return Validation.VALIDATED_IEC61937_AC3; }
    @Override public String validationEvidence() {
        return "CM6206 0d8c:0102, S/PDIF selector7/pin3, IN85/alt1 and OUT06/alt4 at48k: "
                + "93/93 AC-3 CRC valid for stereo and5.1 in diagnostic duplex, 2026-10-09. "
                + "Runtime parser must validate every burst; continuous duration is separately measured.";
    }

    /** Takes ownership of the already authorized connection, including on failure. */
    @Override public void open(UsbDevice device, UsbDeviceConnection opened) throws IOException {
        synchronized (lifecycle) {
        synchronized (lifecycle) {
            require(connection == null && !closed && !closing, "Transporte já utilizado");
            require(device != null && opened != null, "Interface USB ausente");
            connection = opened;
        }
        try {
            require(device.getVendorId() == 0x0d8c && device.getProductId() == 0x0102, "CM6206 diferente da unidade validada");
            findInterfaces(device);
            validateRawDescriptors(opened.getRawDescriptors());
            File hardware = journal.getParentFile();
            require(hardware.isDirectory() || hardware.mkdirs(), "Diretório de recuperação indisponível");
            controlClaimed = nativeClaimInterface(opened.getFileDescriptor(),controlInterface.getId());
            require(controlClaimed, "Não foi possível acessar o controle UAC");
            inputClaimed = nativeClaimInterface(opened.getFileDescriptor(),inputInterface.getId());
            require(inputClaimed, "Não foi possível acessar a captura UAC");
            outputClaimed = nativeClaimInterface(opened.getFileDescriptor(),outputInterface.getId());
            require(outputClaimed, "Não foi possível acessar a saída USB6");
            recoverDiagnosticJournal();
            int current = readPin();
            recoverInterruptedJournal(current);
            originalPin = readPin();
            saveJournal(false);
            if (originalPin != 3) {
                /* Journal is durable before the first hardware mutation. */
                sourceTouched = true;
                setPin(3);
            }
            require(readPin() == 3, "Entrada S/PDIF não confirmada");
            require(opened.setInterface(inputInterface), "Alternate da captura falhou");
            setFrequency(0x85);
            require(opened.setInterface(outputInterface), "Alternate da saída falhou");
            setFrequency(0x06);
            long nativeHandle = nativeOpen(opened.getFileDescriptor());
            synchronized (lifecycle) {
                require(!closing && !closed, "Abertura USB cancelada");
                handle = nativeHandle;
            }
        } catch (Exception failure) {
            try { close(); } catch (IOException restoration) { failure.addSuppressed(restoration); }
            if (failure instanceof IOException) throw (IOException)failure;
            throw new IOException("Falha ao abrir USB óptico", failure);
        }
        }
    }

    private void findInterfaces(UsbDevice device) throws IOException {
        for (int index = 0; index < device.getInterfaceCount(); index++) {
            UsbInterface candidate = device.getInterface(index);
            if (candidate.getInterfaceClass() != UsbConstants.USB_CLASS_AUDIO) continue;
            if (candidate.getId() == 0 && candidate.getAlternateSetting() == 0 && candidate.getInterfaceSubclass() == 1)
                controlInterface = candidate;
            if (candidate.getInterfaceSubclass() != 2) continue;
            for (int epIndex = 0; epIndex < candidate.getEndpointCount(); epIndex++) {
                UsbEndpoint ep = candidate.getEndpoint(epIndex);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_ISOC || ep.getInterval() != 1) continue;
                if (candidate.getId() == 2 && candidate.getAlternateSetting() == 1 && ep.getAddress() == 0x85 && ep.getMaxPacketSize() == 200)
                    inputInterface = candidate;
                if (candidate.getId() == 1 && candidate.getAlternateSetting() == 4 && ep.getAddress() == 0x06 && ep.getMaxPacketSize() == 584)
                    outputInterface = candidate;
            }
        }
        require(controlInterface != null && inputInterface != null && outputInterface != null, "Topologia USB diferente da unidade validada");
    }

    static void validateRawDescriptors(byte[] descriptors) throws IOException {
        require(descriptors != null, "Descritores USB indisponíveis");
        boolean selector = false, outputFormat = false, inputFormat = false;
        int interfaceId = -1, alternate = -1;
        for (int index = 0; index < descriptors.length;) {
            int length = descriptors[index] & 255;
            require(length >= 2 && index + length <= descriptors.length, "Descritor USB inválido");
            int type = descriptors[index + 1] & 255;
            if (type == 4 && length >= 9) { interfaceId = descriptors[index + 2] & 255; alternate = descriptors[index + 3] & 255; }
            if (interfaceId == 0 && type == 0x24 && length >= 10 && (descriptors[index + 2] & 255) == 5
                    && (descriptors[index + 3] & 255) == 7 && (descriptors[index + 4] & 255) == 4
                    && (descriptors[index + 5] & 255) == 8 && (descriptors[index + 6] & 255) == 15
                    && (descriptors[index + 7] & 255) == 16 && (descriptors[index + 8] & 255) == 2) selector = true;
            if (type == 0x24 && length >= 8 && (descriptors[index + 2] & 255) == 2
                    && (descriptors[index + 3] & 255) == 1) {
                int channels = descriptors[index + 4] & 255;
                boolean pcm16 = (descriptors[index + 5] & 255) == 2 && (descriptors[index + 6] & 255) == 16;
                if (interfaceId == 1 && alternate == 4 && channels == 6 && pcm16) outputFormat = true;
                if (interfaceId == 2 && alternate == 1 && channels == 2 && pcm16) inputFormat = true;
            }
            index += length;
        }
        require(selector && outputFormat && inputFormat, "Seletor e formatos USB não coincidem com a unidade validada");
    }

    private int readPin() throws IOException {
        byte[] pin = new byte[1];
        require(connection.controlTransfer(0xa1, 0x81, 0, 7 << 8, pin, 1, 500) == 1, "GET_CUR do seletor falhou");
        int value = pin[0] & 255; require(value >= 1 && value <= 4, "Seletor UAC inválido"); return value;
    }
    private void setPin(int value) throws IOException {
        byte[] pin = {(byte)value};
        require(connection.controlTransfer(0x21, 1, 0, 7 << 8, pin, 1, 500) == 1, "SET_CUR do seletor falhou");
        require(readPin() == value, "Seletor UAC não confirmou alteração");
    }
    private void setFrequency(int endpoint) throws IOException {
        byte[] frequency = {(byte)0x80, (byte)0xbb, 0};
        require(connection.controlTransfer(0x22, 1, 0x0100, endpoint, frequency, 3, 500) == 3, "Taxa USB 48k não configurada");
    }
    private void recoverInterruptedJournal(int currentPin) throws IOException {
        if (!journal.exists()) return;
        JSONObject saved = readJson(journal);
        if (saved.optBoolean("restored")) return;
        int previous = UsbSelectorRecovery.originalPin(saved.optInt("originalPin", -1),saved.optInt("targetPin", -1),currentPin);
        require(saved.optInt("vendor") == 0x0d8c && saved.optInt("product") == 0x0102,
                "Journal USB anterior inválido");
        if (currentPin != previous) setPin(previous);
        require(readPin() == previous, "Recuperação do seletor não confirmada");
        originalPin = previous; saveJournal(true); originalPin = -1;
    }
    private void recoverDiagnosticJournal() throws IOException {
        if (!diagnosticJournal.exists()) return;
        JSONObject saved = readJson(diagnosticJournal);
        if (saved.optBoolean("restored")) return;
        int current = readPin();
        int previous = UsbSelectorRecovery.originalPin(saved.optInt("originalPin", -1),saved.optInt("targetPin", -1),current);
        if (current != previous) setPin(previous);
        require(readPin() == previous, "Recuperação do teste óptico não confirmada");
        try { saved.put("restored",true); writeJournal(diagnosticJournal,saved); }
        catch (JSONException failure) { throw new IOException("Registro do teste óptico inválido",failure); }
    }
    private void saveJournal(boolean restored) throws IOException {
        try {
            JSONObject value = new JSONObject().put("vendor", 0x0d8c).put("product", 0x0102)
                    .put("originalPin", originalPin).put("targetPin", 3).put("restored", restored)
                    .put("updatedAtMillis", System.currentTimeMillis());
            writeJournal(journal,value);
        } catch (JSONException failure) { throw new IOException("Journal USB inválido", failure); }
    }
    private static void writeJournal(File target,JSONObject value) throws IOException {
            File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(value.toString().getBytes(StandardCharsets.UTF_8)); out.flush(); out.getFD().sync();
            }
            require(temporary.renameTo(target), "Journal USB não foi persistido");
            FileDescriptor directory = null;
            try {
                directory = Os.open(target.getParent(), OsConstants.O_RDONLY, 0);
                Os.fsync(directory);
            } catch (ErrnoException failure) { throw new IOException("Diretório do journal USB não foi sincronizado", failure); }
            finally { if (directory != null) try { Os.close(directory); } catch (ErrnoException ignored) { } }
    }
    private static JSONObject readJson(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int count;
            while ((count = input.read(buffer)) > 0) { require(bytes.size() + count <= 8192, "Journal USB muito grande"); bytes.write(buffer, 0, count); }
            return new JSONObject(bytes.toString("UTF-8"));
        } catch (JSONException failure) { throw new IOException("Journal USB inválido", failure); }
    }

    private long beginCall() throws IOException {
        synchronized (lifecycle) { require(handle != 0 && !closing && !closed, "Transporte USB fechado"); activeCalls++; return handle; }
    }
    private void endCall() { synchronized (lifecycle) { activeCalls--; lifecycle.notifyAll(); } }
    @Override public int read(byte[] destination, int offset, int length, int timeoutMillis) throws IOException {
        long session = beginCall();
        try { return nativeRead(session, destination, offset, length, timeoutMillis); } finally { endCall(); }
    }
    public int write(byte[] pcm16, int offset, int length, int timeoutMillis) throws IOException {
        long session = beginCall();
        try { return nativeWrite(session, pcm16, offset, length, timeoutMillis); } finally { endCall(); }
    }
    /** Gates future URBs immediately; previously submitted audio has at most16ms tail. */
    public void setMuted(boolean value) throws IOException {
        if(value==muted)return;
        long session = beginCall();
        try { nativeSetMuted(session, value); muted = value; } finally { endCall(); }
    }
    public void clearOutput() throws IOException {
        long session = beginCall();
        try { nativeClearOutput(session); } finally { endCall(); }
    }
    public JSONObject statsJson() {
        long session;
        try { session = beginCall(); } catch (IOException ended) { return copyStats(); }
        try { lastStats = jsonStats(nativeStats(session)); } finally { endCall(); }
        return copyStats();
    }
    /** Audio worker fills a reusable primitive buffer; no JSON tree per AC-3 frame. */
    public void readStats(long[] destination)throws IOException{
        if(destination==null||destination.length!=27)throw new IllegalArgumentException("27 USB counters required");
        long session=beginCall();try{nativeStatsInto(session,destination);}finally{endCall();}
    }
    private JSONObject copyStats() {
        try { return new JSONObject(lastStats.toString()).put("gateMuted", muted).put("restorationError", restorationError); }
        catch (JSONException ignored) { return new JSONObject(); }
    }
    private void finalStat(String name, Object value) {
        try { lastStats = new JSONObject(lastStats.toString()).put(name, value); } catch (JSONException ignored) { }
    }
    private static JSONObject jsonStats(long[] values) {
        JSONObject json = new JSONObject();
        if (values == null || values.length != 27) return json;
        String[] names = {"inputBytes", "inputPackets", "outputBytes", "outputPackets", "silenceFrames", "underrunEvents",
                "inputBufferedBytes", "outputBufferedBytes", "errno", "stopped", "inputPacketErrors", "outputPacketErrors", "inputSubmittedUrbs", "inputCompletedUrbs",
                "gatedSilenceFrames", "primingSilenceFrames", "outputPriming", "refillCount", "currentGate",
                "inputSequenceGaps", "outputSequenceGaps", "inputLastStartFrame", "outputLastStartFrame",
                "inputFrameTimingKnown", "outputFrameTimingKnown", "outputRequestedBytes", "outputByteAccountingKnown"};
        try {
            for (int index = 0; index < names.length; index++) json.put(names[index], values[index]);
            json.put("outputQueuedFrames", values[7] / 12).put("submittedOutputLatencyMs", 16);
        } catch (JSONException ignored) { }
        return json;
    }

    @Override public void close() throws IOException {
        long session; boolean interrupted = false;
        synchronized (lifecycle) {
            while (closing && !closed) try { lifecycle.wait(); } catch (InterruptedException ignored) { interrupted = true; }
            if (closed) { if (interrupted) Thread.currentThread().interrupt(); return; }
            closing = true; session = handle;
        }
        IOException failure = null;
        try {
            if (session != 0) {
                nativeStop(session);
                synchronized (lifecycle) { while (activeCalls != 0) try { lifecycle.wait(); } catch (InterruptedException ignored) { interrupted = true; } }
                lastStats = jsonStats(nativeStats(session));
                int result = nativeDestroy(session);
                finalStat("errno", result);
                if (result != 0) failure = new IOException("Sessão USB encerrada com errno=" + result);
            }
            if (connection != null) {
                try {
                    if (sourceTouched && originalPin >= 1) setPin(originalPin);
                    if (originalPin >= 1) { require(readPin() == originalPin, "Restauração do seletor não confirmada"); saveJournal(true); finalStat("originalSelectorPin", originalPin); finalStat("sourceRestored", true); }
                } catch (IOException restore) { finalStat("sourceRestored", false); restorationError = restore.toString(); if (failure == null) failure = restore; else failure.addSuppressed(restore); }
                try {
                    if (outputClaimed) finalStat("outputReleased", connection.releaseInterface(outputInterface));
                    if (inputClaimed) finalStat("inputReleased", connection.releaseInterface(inputInterface));
                    if (controlClaimed) finalStat("controlReleased", connection.releaseInterface(controlInterface));
                    finalStat("reconnectAudioControlResult", RawUsbCapture.reconnect(connection.getFileDescriptor(), 0));
                } finally { connection.close(); }
            }
        } finally {
            synchronized (lifecycle) { handle = 0; closed = true; closing = false; lifecycle.notifyAll(); }
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (failure != null) throw failure;
    }
    private static void require(boolean condition, String detail) throws IOException { if (!condition) throw new IOException(detail); }
    private static native long nativeOpen(int fd) throws IOException;
    private static native boolean nativeClaimInterface(int fd,int interfaceId)throws IOException;
    private static native int nativeRead(long session, byte[] destination, int offset, int length, int timeout) throws IOException;
    private static native int nativeWrite(long session, byte[] source, int offset, int length, int timeout) throws IOException;
    private static native long[] nativeStats(long session);
    private static native void nativeStatsInto(long session,long[] destination);
    private static native void nativeSetMuted(long session, boolean muted);
    private static native void nativeClearOutput(long session);
    private static native void nativeStop(long session);
    private static native int nativeDestroy(long session);
    /** Instrumentation verification only; never touches USB or submits output. */
    public static int softwareSelfTest() throws IOException {
        int result = nativeSoftwareSelfTest();
        require(result == 31, "Falha no FIFO/gate/priming nativo: " + result);
        return result;
    }
    private static native int nativeSoftwareSelfTest();
}
