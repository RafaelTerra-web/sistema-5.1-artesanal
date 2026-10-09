package br.com.sistema51.a34.usb;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * Bounded, read-only register diagnostic for the CM6206 (0d8c:0102).
 *
 * Protocol facts: https://github.com/vestom/cm6206ctl/blob/master/cm6206ctl.c
 * Independently implemented using Android's USB host API. The read command's
 * OUTPUT report requests a register value; it does not write that register.
 * Android transmits four payload bytes, without Windows' synthetic report-ID 0.
 * No audio interface, alternate setting, mixer, initialization or register-write
 * operation is used. Call on a worker thread after USB permission is granted.
 */
public final class Cm6206HidControl {
    private static final int VENDOR_ID = 0x0d8c;
    private static final int PRODUCT_ID = 0x0102;
    private static final int REGISTER_COUNT = 6;
    private static final int REGISTER_TIMEOUT_MS = 1000;
    private static final int MAX_REPORTS_PER_REGISTER = 32;
    private static final int INPUT_WIRE_BYTES = 3;
    private static final int OUTPUT_WIRE_BYTES = 4;

    private Cm6206HidControl() { }

    /** Does not request permission, detach kernel drivers, or modify registers. */
    public static JSONObject readSnapshot(Context context, int deviceId) {
        JSONObject result = new JSONObject();
        JSONArray registers = new JSONArray();
        put(result, "readOnly", true);
        put(result, "deviceId", deviceId);
        put(result, "usbId", "0d8c:0102");
        put(result, "registers", registers);
        put(result, "status", "not_started");
        put(result, "protocolSource", "https://github.com/vestom/cm6206ctl/blob/master/cm6206ctl.c");
        put(result, "registerMeaning", "Valores brutos; leitura HID não comprova captura óptica ou áudio multicanal.");

        if (Looper.myLooper() == Looper.getMainLooper()) {
            return failure(result, "worker_thread_required", "Execute o diagnóstico HID fora da thread da interface.");
        }
        if (context == null) return failure(result, "error", "Context ausente.");
        UsbManager manager = (UsbManager) context.getApplicationContext().getSystemService(Context.USB_SERVICE);
        if (manager == null) return failure(result, "unsupported", "USB host indisponível.");

        UsbDevice device = null;
        for (UsbDevice candidate : manager.getDeviceList().values()) {
            if (candidate.getDeviceId() == deviceId) { device = candidate; break; }
        }
        if (device == null) return failure(result, "device_absent", "Dispositivo USB desconectado.");
        if (device.getVendorId() != VENDOR_ID || device.getProductId() != PRODUCT_ID) {
            return failure(result, "unsupported", "Protocolo permitido apenas para 0d8c:0102.");
        }
        put(result, "deviceName", device.getDeviceName());
        put(result, "permission", manager.hasPermission(device));
        if (!manager.hasPermission(device)) {
            return failure(result, "permission_required", "Autorize o USB no Android antes deste diagnóstico.");
        }

        UsbInterface hid = null;
        UsbEndpoint input = null;
        for (int index = 0; index < device.getInterfaceCount() && hid == null; index++) {
            UsbInterface candidate = device.getInterface(index);
            if (candidate.getInterfaceClass() != UsbConstants.USB_CLASS_HID) continue;
            // Do not select an alternate setting. Diagnose only the default HID interface.
            if (candidate.getAlternateSetting() != 0) continue;
            for (int endpointIndex = 0; endpointIndex < candidate.getEndpointCount(); endpointIndex++) {
                UsbEndpoint endpoint = candidate.getEndpoint(endpointIndex);
                if (endpoint.getDirection() == UsbConstants.USB_DIR_IN
                        && endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_INT
                        && endpoint.getMaxPacketSize() >= INPUT_WIRE_BYTES) {
                    hid = candidate;
                    input = endpoint;
                    break;
                }
            }
        }
        if (hid == null) return failure(result, "unsupported", "Interface HID com interrupt IN não encontrada.");
        JSONObject caps = new JSONObject();
        put(caps, "interfaceId", hid.getId());
        put(caps, "interfaceClass", hid.getInterfaceClass());
        put(caps, "alternate", hid.getAlternateSetting());
        put(caps, "inputEndpoint", input.getAddress());
        put(caps, "maxPacketSize", input.getMaxPacketSize());
        put(caps, "interval", input.getInterval());
        put(caps, "expectedInputWireBytes", INPUT_WIRE_BYTES);
        put(caps, "outputWireBytes", OUTPUT_WIRE_BYTES);
        put(caps, "reportId", 0);
        put(caps, "reportIdPrefixedOnWire", false);
        put(caps, "claimForce", false);
        put(result, "caps", caps);

        UsbDeviceConnection connection = null;
        UsbRequest request = null;
        boolean claimed = false;
        long started = SystemClock.elapsedRealtime();
        try {
            connection = manager.openDevice(device);
            if (connection == null) return failure(result, "open_failed", "Não foi possível abrir o dispositivo USB.");
            // Claim only HID, without detaching a bound driver. Audio stays with Android.
            claimed = connection.claimInterface(hid, false);
            put(caps, "claimed", claimed);
            if (!claimed) return failure(result, "hid_busy", "Interface HID ocupada; nenhum driver foi removido.");
            request = new UsbRequest();
            if (!request.initialize(connection, input)) throw new IOException("Falha ao inicializar interrupt IN.");

            for (int register = 0; register < REGISTER_COUNT; register++) {
                JSONObject item = new JSONObject();
                JSONArray reports = new JSONArray();
                registers.put(item);
                put(item, "register", register);
                put(item, "ok", false);
                put(item, "rawReports", reports);
                byte[] command = readCommand(register);
                put(item, "requestHex", hex(command, command.length));
                long deadline = SystemClock.elapsedRealtime() + REGISTER_TIMEOUT_MS;
                ByteBuffer buffer = ByteBuffer.allocateDirect(input.getMaxPacketSize());
                try {
                    // Queue before the control request so an immediate reply cannot be missed.
                    if (!request.queue(buffer)) throw new IOException("Falha ao enfileirar interrupt IN.");
                    int sent = connection.controlTransfer(0x21, 0x09, 0x0200, hid.getId(),
                            command, command.length, remainingMillis(deadline));
                    put(item, "controlTransferBytes", sent);
                    if (sent != command.length) throw new IOException("SET_REPORT de leitura retornou " + sent + " bytes.");
                    boolean received = false;
                    for (int reportIndex = 0; reportIndex < MAX_REPORTS_PER_REGISTER; reportIndex++) {
                        UsbRequest completed = connection.requestWait(remainingMillis(deadline));
                        if (completed != request) throw new IOException("Interrupt IN não concluiu a solicitação esperada.");
                        int count = buffer.position();
                        byte[] reply = new byte[count];
                        buffer.flip();
                        buffer.get(reply);
                        reports.put(hex(reply, count));
                        if (isRegisterReply(reply, count)) {
                            int value = registerValue(reply, count);
                            put(item, "value", value);
                            put(item, "valueHex", String.format(Locale.ROOT, "0x%04X", value));
                            put(item, "replyHex", hex(reply, count));
                            put(item, "ok", true);
                            received = true;
                            break;
                        }
                        // Button/event reports can arrive first. Keep their raw bytes and
                        // wait under the same deadline, without sending another command.
                        buffer.clear();
                        if (!request.queue(buffer)) throw new IOException("Falha ao reenfileirar interrupt IN.");
                    }
                    if (!received) throw new IOException("Limite de eventos HID sem resposta de registrador.");
                } catch (IOException | TimeoutException | RuntimeException error) {
                    put(item, "error", errorText(error));
                    // A timed-out reply has no reliable register address. Stop here so it
                    // cannot be misattributed to a subsequent register.
                    return failure(result, "read_failed", "Registro " + register + ": " + errorText(error));
                }
            }
            put(result, "status", "ok");
            put(result, "registerCount", registers.length());
            return result;
        } catch (IOException | RuntimeException error) {
            return failure(result, "error", errorText(error));
        } finally {
            if (request != null) {
                try { request.cancel(); } catch (RuntimeException error) { cleanupError(result, error); }
                try { request.close(); } catch (RuntimeException error) { cleanupError(result, error); }
            }
            if (claimed && connection != null) {
                try { put(caps, "released", connection.releaseInterface(hid)); }
                catch (RuntimeException error) { cleanupError(result, error); }
            }
            if (connection != null) {
                try { connection.close(); } catch (RuntimeException error) { cleanupError(result, error); }
            }
            put(result, "elapsedMs", SystemClock.elapsedRealtime() - started);
        }
    }

    static byte[] readCommand(int register) {
        if (register < 0 || register >= REGISTER_COUNT) throw new IllegalArgumentException("Registro fora de 0..5.");
        return new byte[] {0x30, 0, 0, (byte) register};
    }

    static boolean isRegisterReply(byte[] reply, int count) {
        return reply != null && count == INPUT_WIRE_BYTES && count <= reply.length
                && ((reply[0] & 0xff) & 0xe0) == 0x20;
    }

    static int registerValue(byte[] reply, int count) {
        if (!isRegisterReply(reply, count)) throw new IllegalArgumentException("Resposta de registrador incompatível.");
        return (reply[1] & 0xff) | ((reply[2] & 0xff) << 8);
    }

    private static int remainingMillis(long deadline) throws TimeoutException {
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) throw new TimeoutException("Prazo de 1000 ms para leitura do registrador esgotado.");
        return (int) Math.min(REGISTER_TIMEOUT_MS, remaining);
    }

    private static JSONObject failure(JSONObject result, String status, String message) {
        put(result, "status", status);
        put(result, "error", message);
        return result;
    }

    private static String errorText(Exception error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static void cleanupError(JSONObject result, RuntimeException error) {
        JSONArray errors = result.optJSONArray("cleanupErrors");
        if (errors == null) { errors = new JSONArray(); put(result, "cleanupErrors", errors); }
        errors.put(errorText(error));
    }

    private static String hex(byte[] bytes, int count) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < count; index++) {
            if (index != 0) text.append('-');
            text.append(String.format(Locale.ROOT, "%02X", bytes[index] & 0xff));
        }
        return text.toString();
    }

    private static void put(JSONObject json, String name, Object value) {
        try { json.put(name, value); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
}
