package br.com.sistema51.a34.usb;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** USB topology and explicit permission requests. No endpoint is claimed here. */
public final class UsbAudioController implements AutoCloseable {
    public interface Listener {
        void onUsbChanged();
        void onUsbDetached(int deviceId);
    }

    private final Context context;
    private final UsbManager manager;
    private final String permissionAction;
    private final Map<Integer, JSONObject> descriptorCache = new HashMap<>();
    private volatile Listener listener;
    private volatile String lastPermissionResult = "Nenhuma solicitação de permissão USB.";
    private boolean receiverRegistered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            UsbDevice device;
            if (Build.VERSION.SDK_INT >= 33) {
                device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
            } else {
                device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            }
            if (permissionAction.equals(intent.getAction())) {
                lastPermissionResult = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        ? "Permissão USB concedida." : "Permissão USB recusada.";
                synchronized (descriptorCache) { descriptorCache.clear(); }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                if (device != null) {
                    synchronized (descriptorCache) { descriptorCache.remove(device.getDeviceId()); }
                    Listener current = listener;
                    if (current != null) current.onUsbDetached(device.getDeviceId());
                }
            }
            Listener current = listener;
            if (current != null) current.onUsbChanged();
        }
    };

    public UsbAudioController(Context context) {
        this.context = context.getApplicationContext();
        manager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
        permissionAction = this.context.getPackageName() + ".USB_AUDIO_PERMISSION";
        IntentFilter filter = new IntentFilter(permissionAction);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            this.context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            this.context.registerReceiver(receiver, filter);
        }
        receiverRegistered = true;
    }

    public void setListener(Listener listener) { this.listener = listener; }

    public List<UsbDevice> listDevices() {
        List<UsbDevice> devices = manager == null ? new ArrayList<>()
                : new ArrayList<>(manager.getDeviceList().values());
        Collections.sort(devices, Comparator.comparingInt(UsbDevice::getDeviceId));
        return devices;
    }

    public UsbDevice findDevice(int deviceId) {
        for (UsbDevice device : listDevices()) {
            if (device.getDeviceId() == deviceId) return device;
        }
        return null;
    }

    public boolean hasPermission(int deviceId) {
        UsbDevice device = findDevice(deviceId);
        return device != null && manager.hasPermission(device);
    }

    /** Call only from the user's USB permission action. */
    public boolean requestPermission(int deviceId) {
        UsbDevice device = findDevice(deviceId);
        if (device == null) {
            lastPermissionResult = "Dispositivo USB ausente.";
            return false;
        }
        if (manager.hasPermission(device)) {
            lastPermissionResult = "Permissão USB já concedida.";
            return true;
        }
        // USB permission's result needs system-added extras. Package prevents implicit delivery.
        Intent intent = new Intent(permissionAction).setPackage(context.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, deviceId, intent, flags);
        lastPermissionResult = "Aguardando autorização USB no Android.";
        manager.requestPermission(device, pendingIntent);
        return true;
    }

    public static boolean isAudioCandidate(UsbDevice device) {
        if (device.getDeviceClass() == UsbConstants.USB_CLASS_AUDIO) return true;
        for (int index = 0; index < device.getInterfaceCount(); index++) {
            if (device.getInterface(index).getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO) return true;
        }
        return false;
    }

    public JSONObject snapshotJson() {
        JSONObject result = new JSONObject();
        JSONArray devices = new JSONArray();
        int candidates = 0;
        try {
            for (UsbDevice device : listDevices()) {
                JSONObject item = describe(device);
                devices.put(item);
                if (isAudioCandidate(device)) candidates++;
            }
            result.put("usbDevices", devices);
            result.put("androidUsbAudioDevices", androidAudioCapabilities());
            result.put("audioCandidates", candidates);
            result.put("permission", lastPermissionResult);
            result.put("usbSummary", candidates == 0 ? "Aguardando CM6206 e hub USB-C."
                    : candidates + " dispositivo(s) USB com interfaces de áudio.");
            result.put("opticalAc3", "Captura óptica AC-3 direta pendente validação da interface; PCM USB disponível.");
            result.put("descriptorMeaning", "Descritores são capacidade anunciada; não validam captura óptica, IEC61937 ou seis canais simultâneos.");
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        return result;
    }

    public String prettyJson() {
        try { return snapshotJson().toString(2); }
        catch (JSONException impossible) { return snapshotJson().toString(); }
    }

    private JSONArray androidAudioCapabilities() throws JSONException {
        JSONArray result = new JSONArray();
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return result;
        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_INPUTS | AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.getType() != AudioDeviceInfo.TYPE_USB_DEVICE && device.getType() != AudioDeviceInfo.TYPE_USB_HEADSET) continue;
            JSONObject item = new JSONObject();
            item.put("id", device.getId());
            item.put("address", device.getAddress());
            item.put("product", device.getProductName());
            item.put("source", device.isSource());
            item.put("sink", device.isSink());
            item.put("sampleRates", integers(device.getSampleRates()));
            item.put("channelCounts", integers(device.getChannelCounts()));
            item.put("channelMasks", integers(device.getChannelMasks()));
            item.put("channelIndexMasks", integers(device.getChannelIndexMasks()));
            item.put("encodings", integers(device.getEncodings()));
            boolean sixChannels = false;
            for (int count : device.getChannelCounts()) if (count == 6) sixChannels = true;
            for (int mask : device.getChannelMasks()) if (Integer.bitCount(mask) == 6) sixChannels = true;
            for (int mask : device.getChannelIndexMasks()) if (Integer.bitCount(mask) == 6) sixChannels = true;
            item.put("advertisesSixChannels", sixChannels);
            item.put("runtimeValidation", "Pendente até AudioRecord/AudioTrack confirmar formato e rota.");
            result.put(item);
        }
        return result;
    }

    private static JSONArray integers(int[] values) {
        JSONArray result = new JSONArray();
        for (int value : values) result.put(value);
        return result;
    }

    private JSONObject describe(UsbDevice device) throws JSONException {
        JSONObject item = new JSONObject();
        item.put("deviceId", device.getDeviceId());
        item.put("name", device.getDeviceName());
        item.put("product", device.getProductName() == null ? "USB" : device.getProductName());
        item.put("manufacturer", device.getManufacturerName() == null ? "" : device.getManufacturerName());
        item.put("vendorId", device.getVendorId());
        item.put("productId", device.getProductId());
        item.put("usbId", String.format(java.util.Locale.ROOT, "%04x:%04x", device.getVendorId(), device.getProductId()));
        item.put("audioCandidate", isAudioCandidate(device));
        item.put("cm6206Candidate", device.getVendorId() == 0x0d8c && isAudioCandidate(device));
        item.put("permission", manager.hasPermission(device));
        JSONArray interfaces = new JSONArray();
        boolean captureEndpoint = false;
        boolean playbackEndpoint = false;
        for (int index = 0; index < device.getInterfaceCount(); index++) {
            UsbInterface usbInterface = device.getInterface(index);
            JSONObject description = new JSONObject();
            description.put("id", usbInterface.getId());
            description.put("alternate", usbInterface.getAlternateSetting());
            description.put("class", usbInterface.getInterfaceClass());
            description.put("subclass", usbInterface.getInterfaceSubclass());
            description.put("protocol", usbInterface.getInterfaceProtocol());
            JSONArray endpoints = new JSONArray();
            for (int endpointIndex = 0; endpointIndex < usbInterface.getEndpointCount(); endpointIndex++) {
                UsbEndpoint endpoint = usbInterface.getEndpoint(endpointIndex);
                JSONObject endpointDescription = new JSONObject();
                endpointDescription.put("address", endpoint.getAddress());
                endpointDescription.put("direction", endpoint.getDirection() == UsbConstants.USB_DIR_IN ? "IN (captura)" : "OUT (reprodução)");
                endpointDescription.put("type", endpoint.getType());
                endpointDescription.put("maxPacketSize", endpoint.getMaxPacketSize());
                endpointDescription.put("interval", endpoint.getInterval());
                endpoints.put(endpointDescription);
                if (usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO
                        && endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC) {
                    if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) captureEndpoint = true;
                    else playbackEndpoint = true;
                }
            }
            description.put("endpoints", endpoints);
            interfaces.put(description);
        }
        item.put("interfaces", interfaces);
        item.put("advertisesIsoCapture", captureEndpoint);
        item.put("advertisesIsoPlayback", playbackEndpoint);
        item.put("fullDuplexCandidate", captureEndpoint && playbackEndpoint);
        if (manager.hasPermission(device)) item.put("audioFormatDescriptors", formatDescriptors(device));
        return item;
    }

    private JSONObject formatDescriptors(UsbDevice device) throws JSONException {
        synchronized (descriptorCache) {
            JSONObject cached = descriptorCache.get(device.getDeviceId());
            if (cached != null) return cached;
        }
        JSONObject result = new JSONObject();
        JSONArray formats = new JSONArray();
        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) {
            result.put("status", "Não foi possível ler os descritores.");
            return result;
        }
        try {
            byte[] bytes = connection.getRawDescriptors();
            int interfaceClass = -1;
            int interfaceSubclass = -1;
            int interfaceProtocol = -1;
            int interfaceId = -1;
            int alternate = -1;
            for (int offset = 0; bytes != null && offset + 2 <= bytes.length;) {
                int length = bytes[offset] & 255;
                int type = bytes[offset + 1] & 255;
                if (length < 2 || offset + length > bytes.length) break;
                if (type == 4 && length >= 9) {
                    interfaceId = bytes[offset + 2] & 255;
                    alternate = bytes[offset + 3] & 255;
                    interfaceClass = bytes[offset + 5] & 255;
                    interfaceSubclass = bytes[offset + 6] & 255;
                    interfaceProtocol = bytes[offset + 7] & 255;
                } else if (type == 0x24 && length >= 8 && interfaceClass == UsbConstants.USB_CLASS_AUDIO
                        && interfaceSubclass == 2 && interfaceProtocol == 0 && (bytes[offset + 2] & 255) == 2
                        && (bytes[offset + 3] & 255) == 1) {
                    JSONObject format = new JSONObject();
                    format.put("usbAudioClass", "UAC1 type I (anunciado)");
                    format.put("interface", interfaceId);
                    format.put("alternate", alternate);
                    format.put("channels", bytes[offset + 4] & 255);
                    format.put("bytesPerSample", bytes[offset + 5] & 255);
                    format.put("bitsPerSample", bytes[offset + 6] & 255);
                    int count = bytes[offset + 7] & 255;
                    JSONArray rates = new JSONArray();
                    int limit = count == 0 ? 2 : count;
                    for (int rate = 0; rate < limit && 8 + (rate + 1) * 3 <= length; rate++) {
                        int base = offset + 8 + rate * 3;
                        rates.put((bytes[base] & 255) | ((bytes[base + 1] & 255) << 8) | ((bytes[base + 2] & 255) << 16));
                    }
                    format.put(count == 0 ? "sampleRateRange" : "sampleRates", rates);
                    formats.put(format);
                }
                offset += length;
            }
            result.put("status", "Descritores lidos sem reivindicar endpoints.");
            result.put("uac1TypeIFormats", formats);
            result.put("note", "UAC2 e IEC61937 requerem validação específica; nenhum descritor prova AC-3 bruto.");
        } finally { connection.close(); }
        synchronized (descriptorCache) { descriptorCache.put(device.getDeviceId(), result); }
        return result;
    }

    @Override public void close() {
        listener = null;
        if (receiverRegistered) {
            context.unregisterReceiver(receiver);
            receiverRegistered = false;
        }
    }
}
