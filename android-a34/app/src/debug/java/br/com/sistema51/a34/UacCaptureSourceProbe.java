package br.com.sistema51.a34;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Debug-only UAC1 capture-source inspection. No claims, alternate-setting changes,
 * writes, kernel detach, audio streams or HID commands. IDs come from descriptors.
 * Protocol: USB Audio 1.0 sections 4.3.2 and 5.2.2.3:
 * https://www.usb.org/sites/default/files/audio10.pdf
 * A descriptor path to S/PDIF is not evidence of an optical signal or intact AC-3.
 */
public final class UacCaptureSourceProbe {
    private static final int TIMEOUT_MS = 500, MAX_BYTES = 65536, MAX_ENTITIES = 64, MAX_SELECTORS = 8;
    private UacCaptureSourceProbe() { }

    public static JSONObject readSnapshot(Context context, int deviceId) {
        JSONObject report = baseReport();
        put(report, "deviceId", deviceId);
        UsbDeviceConnection connection = null;
        try {
            UsbManager manager = (UsbManager) context.getApplicationContext().getSystemService(Context.USB_SERVICE);
            if (manager == null) return fail(report, "usb_unavailable", "USB host indisponível.");
            UsbDevice target = null;
            for (UsbDevice device : manager.getDeviceList().values()) {
                if (device.getDeviceId() == deviceId) target = device;
            }
            if (target == null) return fail(report, "device_absent", "CM6206 desconectada.");
            if (target.getVendorId() != 0x0d8c || target.getProductId() != 0x0102)
                return fail(report, "unsupported_device", "Probe permitido apenas para 0d8c:0102.");
            put(report, "usbId", "0d8c:0102");
            put(report, "permission", manager.hasPermission(target));
            if (!manager.hasPermission(target)) return fail(report, "permission_required", "Autorize o USB no app.");
            connection = manager.openDevice(target);
            if (connection == null) return fail(report, "open_failed", "Não foi possível abrir o USB.");
            byte[] raw = connection.getRawDescriptors();
            Parsed parsed = parse(raw);
            put(report, "rawDescriptorBytes", raw == null ? 0 : raw.length);
            put(report, "parseComplete", parsed.errors.length() == 0);
            put(report, "errors", parsed.errors);
            put(report, "audioControlTopologies", topologyJson(parsed));
            if (parsed.errors.length() != 0)
                return fail(report, "invalid_or_ambiguous_descriptors", "Descritores incompletos ou ambíguos; nenhum GET_CUR executado.");
            // Standard device read: identifies which descriptor configuration is active.
            byte[] currentConfiguration = new byte[1];
            int configRead = connection.controlTransfer(0x80, 0x08, 0, 0, currentConfiguration, 1, TIMEOUT_MS);
            put(report, "getConfigurationBytes", configRead);
            if (configRead != 1 || unsigned(currentConfiguration[0]) == 0)
                return fail(report, "configuration_unconfirmed", "Configuração ativa não confirmada; nenhum GET_CUR executado.");
            int activeConfiguration = unsigned(currentConfiguration[0]);
            put(report, "activeConfiguration", activeConfiguration);
            JSONArray captures = new JSONArray();
            put(report, "captureSources", captures);
            int selectorReads = 0;
            Set<String> queried = new HashSet<>();
            Map<String, Integer> values = new LinkedHashMap<>();
            for (Group group : parsed.groups) {
                if (group.configuration != activeConfiguration || group.version != 0x0100) continue;
                for (Stream stream : parsed.streams) {
                    if (stream.configuration != group.configuration || !stream.isochronousInput
                            || !group.collection.contains(stream.id)) continue;
                    Entity root = group.entities.get(stream.terminal);
                    JSONObject capture = new JSONObject();
                    captures.put(capture);
                    put(capture, "configuration", group.configuration);
                    put(capture, "audioControlInterface", group.id);
                    put(capture, "streamingInterface", stream.id);
                    put(capture, "streamingAlternate", stream.alternate);
                    put(capture, "usbCaptureTerminalId", stream.terminal);
                    put(capture, "formatTag", stream.formatTag);
                    if (root == null || root.subtype != 3 || root.terminalType != 0x0101) {
                        put(capture, "status", "capture_terminal_unresolved");
                        continue;
                    }
                    Set<Integer> reachable = new HashSet<>();
                    JSONArray pathErrors = new JSONArray();
                    walk(group, root.id, new HashSet<Integer>(), reachable, pathErrors);
                    put(capture, "topologyErrors", pathErrors);
                    if (pathErrors.length() != 0) { put(capture, "status", "ambiguous_capture_topology"); continue; }
                    JSONArray selectors = new JSONArray();
                    put(capture, "selectors", selectors);
                    for (Integer entityId : reachable) {
                        Entity entity = group.entities.get(entityId);
                        if (entity.subtype != 5) continue;
                        String key = group.configuration + ":" + group.id + ":" + entity.id;
                        if (!queried.contains(key)) {
                            if (++selectorReads > MAX_SELECTORS)
                                return fail(report, "selector_limit", "Limite de oito seletores atingido.");
                            queried.add(key);
                            byte[] state = new byte[1];
                            int read = connection.controlTransfer(0xa1, 0x81, 0,
                                    (entity.id << 8) | group.id, state, 1, TIMEOUT_MS);
                            int pin = read == 1 ? unsigned(state[0]) : -1;
                            entity.getCurBytes = read;
                            entity.selectedPin = pin;
                            if (pin >= 1 && pin <= entity.sources.length) values.put(key, pin);
                        }
                    }
                    // Resolve labels only after all reachable nested selectors were read.
                    for (Integer entityId : reachable) {
                        Entity entity = group.entities.get(entityId);
                        if (entity.subtype != 5) continue;
                        JSONObject selector = entityJson(entity);
                        put(selector, "requestType", "0xA1"); put(selector, "request", "0x81 GET_CUR");
                        put(selector, "wValue", 0); put(selector, "wIndex", (entity.id << 8) | group.id);
                        put(selector, "getCurBytes", entity.getCurBytes);
                        put(selector, "selectedPinOneBased", entity.selectedPin);
                        boolean valid = entity.selectedPin >= 1 && entity.selectedPin <= entity.sources.length;
                        put(selector, "selectorStateValid", valid);
                        if (valid) put(selector, "selectedSourceId", entity.sources[entity.selectedPin - 1]);
                        JSONArray options = new JSONArray();
                        for (int i = 0; i < entity.sources.length; i++) {
                            Selected possible = new Selected();
                            resolve(group, entity.sources[i], values, new HashSet<Integer>(), possible);
                            JSONObject option = new JSONObject();
                            put(option, "pinOneBased", i + 1); put(option, "sourceId", entity.sources[i]);
                            put(option, "sourceKind", sourceKind(possible));
                            put(option, "possibleInputTerminals", possible.terminals);
                            put(option, "opaqueOrMixedPath", possible.opaque);
                            options.put(option);
                        }
                        put(selector, "options", options);
                        selectors.put(selector);
                    }
                    Selected selected = new Selected();
                    resolve(group, root.id, values, new HashSet<Integer>(), selected);
                    put(capture, "possibleSelectedInputTerminals", selected.terminals);
                    put(capture, "opaqueOrMixedPath", selected.opaque);
                    put(capture, "selectorStateResolved", selected.resolved);
                    put(capture, "sourceKind", sourceKind(selected));
                    put(capture, "status", selected.resolved ? "read_complete" : "selector_read_unresolved");
                }
            }
            put(report, "selectorGetCurCalls", selectorReads);
            put(report, "ok", captures.length() != 0);
            put(report, "status", captures.length() == 0 ? "no_uac1_capture_topology" : "read_complete");
            return report;
        } catch (Exception error) {
            return fail(report, "read_failed", error.getClass().getSimpleName() + ": " + error.getMessage());
        } finally { if (connection != null) connection.close(); }
    }

    /** Pure descriptor parser entry point for host-side synthetic tests; no USB access. */
    public static JSONObject inspectDescriptors(byte[] raw) {
        Parsed parsed = parse(raw);
        JSONObject report = baseReport();
        put(report, "parseComplete", parsed.errors.length() == 0);
        put(report, "errors", parsed.errors);
        put(report, "audioControlTopologies", topologyJson(parsed));
        put(report, "selectorGetCurCalls", 0);
        return report;
    }

    private static Parsed parse(byte[] raw) {
        Parsed parsed = new Parsed();
        if (raw == null || raw.length < 18 || raw.length > MAX_BYTES) {
            parsed.errors.put("raw_descriptor_size_invalid"); return parsed;
        }
        if (unsigned(raw[0]) != 18 || unsigned(raw[1]) != 1 || unsigned(raw[17]) == 0) {
            parsed.errors.put("invalid_device_descriptor"); return parsed;
        }
        int configuration = -1, configurationEnd = -1;
        Set<Integer> configurations = new HashSet<>();
        Set<String> streamAlternates = new HashSet<>();
        Group group = null;
        Stream stream = null;
        for (int offset = 0; offset < raw.length;) {
            if (offset + 2 > raw.length) { parsed.errors.put("truncated_descriptor_header_at_" + offset); break; }
            int length = unsigned(raw[offset]), type = unsigned(raw[offset + 1]);
            if (length < 2 || offset + length > raw.length) { parsed.errors.put("truncated_descriptor_at_" + offset); break; }
            if (configurationEnd >= 0 && offset >= configurationEnd) { configuration = -1; group = null; stream = null; }
            if (type == 2) {
                if (configuration >= 0) { parsed.errors.put("nested_configuration_descriptor"); break; }
                if (length < 9) { parsed.errors.put("short_configuration"); break; }
                configuration = unsigned(raw[offset + 5]);
                if (!configurations.add(configuration)) { parsed.errors.put("duplicate_configuration_value"); break; }
                configurationEnd = offset + little(raw, offset + 2);
                if (configuration == 0 || configurationEnd < offset + length || configurationEnd > raw.length) {
                    parsed.errors.put("configuration_extent_invalid"); break;
                }
            } else if (type == 4) {
                group = null; stream = null;
                if (length < 9 || configuration < 0) { parsed.errors.put("invalid_interface_descriptor"); break; }
                int id = unsigned(raw[offset + 2]), alternate = unsigned(raw[offset + 3]);
                int deviceClass = unsigned(raw[offset + 5]), subclass = unsigned(raw[offset + 6]), protocol = unsigned(raw[offset + 7]);
                if (deviceClass == 1 && subclass == 1 && protocol == 0 && alternate == 0) {
                    for (Group earlier : parsed.groups) if (earlier.configuration == configuration && earlier.id == id)
                        parsed.errors.put("duplicate_audio_control_interface_" + id);
                    group = new Group(configuration, id); parsed.groups.add(group);
                } else if (deviceClass == 1 && subclass == 2 && protocol == 0 && alternate != 0) {
                    if (!streamAlternates.add(configuration + ":" + id + ":" + alternate))
                        parsed.errors.put("duplicate_streaming_alternate_" + id + "_" + alternate);
                    stream = new Stream(configuration, id, alternate); parsed.streams.add(stream);
                }
            } else if (type == 0x24 && group != null) {
                group.descriptorBytes += length;
                if (length < 3) { parsed.errors.put("short_audio_control_descriptor"); break; }
                int subtype = unsigned(raw[offset + 2]);
                if (subtype == 1) {
                    if (length < 8 || group.version != -1 || length != 8 + unsigned(raw[offset + 7])) {
                        parsed.errors.put("invalid_audio_control_header"); break;
                    }
                    group.version = little(raw, offset + 3); group.totalBytes = little(raw, offset + 5);
                    for (int n = 0; n < unsigned(raw[offset + 7]); n++) {
                        int id = unsigned(raw[offset + 8 + n]);
                        if (!group.collection.add(id)) parsed.errors.put("duplicate_stream_collection_id_" + id);
                    }
                } else if (group.version == 0x0100) {
                    Entity entity = parseEntity(raw, offset, length, subtype, parsed.errors);
                    if (entity != null) {
                        if (group.entities.size() >= MAX_ENTITIES || group.entities.containsKey(entity.id))
                            parsed.errors.put("duplicate_or_excessive_entity_id_" + entity.id);
                        else group.entities.put(entity.id, entity);
                    }
                }
            } else if (type == 0x24 && stream != null) {
                if (length >= 3 && unsigned(raw[offset + 2]) == 1) {
                    if (length < 7 || stream.terminal != -1) { parsed.errors.put("invalid_audio_stream_general"); break; }
                    stream.terminal = unsigned(raw[offset + 3]); stream.formatTag = little(raw, offset + 5);
                }
            } else if (type == 5 && stream != null) {
                if (length < 7) { parsed.errors.put("short_endpoint_descriptor"); break; }
                if ((unsigned(raw[offset + 2]) & 0x80) != 0 && (unsigned(raw[offset + 3]) & 3) == 1)
                    stream.isochronousInput = true;
            }
            if (configurationEnd >= 0 && offset + length > configurationEnd) {
                parsed.errors.put("descriptor_crosses_configuration_boundary"); break;
            }
            offset += length;
        }
        for (Group current : parsed.groups) {
            if (current.version == -1 || current.descriptorBytes != current.totalBytes)
                parsed.errors.put("audio_control_total_length_mismatch_interface_" + current.id);
            for (Group other : parsed.groups) {
                if (other == current || other.configuration != current.configuration) continue;
                for (Integer id : current.collection) if (other.collection.contains(id))
                    parsed.errors.put("ambiguous_stream_collection_owner_" + id);
            }
        }
        if (configurations.size() != unsigned(raw[17]))
            parsed.errors.put("device_configuration_count_mismatch");
        for (Stream current : parsed.streams) if (current.isochronousInput && current.terminal <= 0)
            parsed.errors.put("capture_stream_without_terminal_link_" + current.id);
        return parsed;
    }

    private static Entity parseEntity(byte[] raw, int offset, int length, int subtype, JSONArray errors) {
        if (length < 4 || unsigned(raw[offset + 3]) == 0) { errors.put("invalid_entity_id"); return null; }
        Entity entity = new Entity(unsigned(raw[offset + 3]), subtype);
        if (subtype == 2 || subtype == 3) {
            int expected = subtype == 2 ? 12 : 9;
            if (length != expected) { errors.put("invalid_terminal_length_" + entity.id); return null; }
            entity.terminalType = little(raw, offset + 4);
            entity.association = unsigned(raw[offset + 6]);
            if (subtype == 3) entity.sources = new int[] {unsigned(raw[offset + 7])};
            else entity.channels = unsigned(raw[offset + 7]);
        } else if (subtype == 4 || subtype == 5) {
            if (length < 5) { errors.put("short_source_unit_" + entity.id); return null; }
            int pins = unsigned(raw[offset + 4]);
            if (pins == 0 || pins > MAX_ENTITIES || length < (subtype == 5 ? 6 : 10) + pins
                    || (subtype == 5 && length != 6 + pins)) {
                errors.put("invalid_source_unit_length_" + entity.id); return null;
            }
            entity.sources = sourceArray(raw, offset + 5, pins);
            entity.opaque = subtype == 4;
        } else if (subtype == 6) {
            if (length < 8) { errors.put("short_feature_unit_" + entity.id); return null; }
            int size = unsigned(raw[offset + 5]);
            if (size < 1 || size > 4 || (length - 7) % size != 0) {
                errors.put("invalid_feature_controls_" + entity.id); return null;
            }
            entity.sources = new int[] {unsigned(raw[offset + 4])};
            entity.controlSize = size;
            for (int at = 6; at < length - 1; at += size) {
                long control = 0;
                for (int b = 0; b < size; b++) control |= (long) unsigned(raw[offset + at + b]) << (8 * b);
                entity.featureControls.put(control);
            }
        } else if (subtype == 7 || subtype == 8) {
            if (length < 8) { errors.put("short_opaque_unit_" + entity.id); return null; }
            int pins = unsigned(raw[offset + 6]);
            if (pins == 0 || pins > MAX_ENTITIES || length < 8 + pins) {
                errors.put("invalid_opaque_unit_sources_" + entity.id); return null;
            }
            entity.sources = sourceArray(raw, offset + 7, pins); entity.opaque = true;
        } else { entity.opaque = true; }
        return entity;
    }

    private static void walk(Group group, int id, Set<Integer> stack, Set<Integer> reachable, JSONArray errors) {
        if (!stack.add(id)) { errors.put("cycle_at_entity_" + id); return; }
        if (reachable.contains(id)) { stack.remove(id); return; }
        Entity entity = group.entities.get(id);
        if (entity == null) { errors.put("missing_entity_" + id); stack.remove(id); return; }
        reachable.add(id);
        for (int source : entity.sources) walk(group, source, stack, reachable, errors);
        stack.remove(id);
    }

    private static void resolve(Group group, int id, Map<String, Integer> values, Set<Integer> stack, Selected selected) {
        if (!stack.add(id)) { selected.opaque = true; selected.resolved = false; return; }
        if (!selected.visited.add(id)) { stack.remove(id); return; }
        Entity entity = group.entities.get(id);
        if (entity == null) { selected.opaque = true; selected.resolved = false; stack.remove(id); return; }
        selected.opaque |= entity.opaque;
        if (entity.subtype == 2) {
            if (selected.ids.add(entity.id)) {
                selected.terminals.put(entityJson(entity)); selected.types.add(entity.terminalType);
            }
        } else if (entity.subtype == 5) {
            Integer pin = values.get(group.configuration + ":" + group.id + ":" + entity.id);
            if (pin == null || pin < 1 || pin > entity.sources.length) selected.resolved = false;
            else resolve(group, entity.sources[pin - 1], values, stack, selected);
        } else {
            if (entity.sources.length == 0) selected.resolved = false;
            for (int source : entity.sources) resolve(group, source, values, stack, selected);
        }
        stack.remove(id);
    }

    private static String sourceKind(Selected source) {
        if (!source.resolved || source.types.isEmpty()) return "unresolved";
        if (source.opaque || source.ids.size() != 1) return "mixed_or_opaque_descriptor_path";
        int type = source.types.iterator().next();
        if (type == 0x0605) return "s_pdif_descriptor_path";
        if (type == 0x0201) return "microphone_descriptor_path";
        if (type == 0x0603) return "line_connector_descriptor_path";
        if (type == 0x0101) return "usb_loopback_descriptor_path";
        return "other_terminal_descriptor_path";
    }

    private static JSONArray topologyJson(Parsed parsed) {
        JSONArray groups = new JSONArray();
        for (Group group : parsed.groups) {
            JSONObject topology = new JSONObject(); groups.put(topology);
            put(topology, "configuration", group.configuration); put(topology, "interfaceId", group.id);
            put(topology, "bcdAudioClass", group.version); put(topology, "uac1Supported", group.version == 0x0100);
            put(topology, "declaredControlBytes", group.totalBytes); put(topology, "observedControlBytes", group.descriptorBytes);
            JSONArray collection = new JSONArray(); for (int id : group.collection) collection.put(id);
            put(topology, "streamingInterfaceCollection", collection);
            JSONArray entities = new JSONArray();
            for (Entity entity : group.entities.values()) entities.put(entityJson(entity));
            put(topology, "entities", entities);
        }
        return groups;
    }

    private static JSONObject entityJson(Entity entity) {
        JSONObject result = new JSONObject();
        put(result, "id", entity.id); put(result, "descriptorSubtype", entity.subtype);
        String[] names = {"unknown", "header", "input_terminal", "output_terminal", "mixer", "selector", "feature", "processing", "extension"};
        put(result, "kind", entity.subtype < names.length ? names[entity.subtype] : "unknown");
        JSONArray sources = new JSONArray(); for (int source : entity.sources) sources.put(source);
        put(result, "sourceIds", sources); put(result, "opaqueProcessing", entity.opaque);
        if (entity.terminalType >= 0) {
            put(result, "terminalType", entity.terminalType);
            put(result, "terminalTypeHex", String.format(java.util.Locale.ROOT, "0x%04X", entity.terminalType));
            put(result, "associatedTerminal", entity.association);
        }
        if (entity.channels >= 0) put(result, "channels", entity.channels);
        if (entity.controlSize != 0) { put(result, "featureControlSize", entity.controlSize); put(result, "featureControlsByChannel", entity.featureControls); }
        return result;
    }

    private static JSONObject baseReport() {
        JSONObject report = new JSONObject();
        put(report, "ok", false); put(report, "kind", "uac1_capture_source_read_only");
        put(report, "readOnly", true); put(report, "claimInterface", false); put(report, "detachKernelDriver", false);
        put(report, "opticalSourceValidated", false); put(report, "ac3Validated", false); put(report, "bitPerfectValidated", false);
        put(report, "protocolSource", "https://www.usb.org/sites/default/files/audio10.pdf");
        put(report, "scope", "Descriptor topology plus GET_CONFIGURATION and reachable capture selectors GET_CUR only. No configuration, mixer, HID or audio-stream writes. Source labels identify descriptor paths, not physical optical content.");
        return report;
    }
    private static JSONObject fail(JSONObject report, String status, String error) {
        put(report, "ok", false); put(report, "status", status); put(report, "error", error); return report;
    }
    private static int[] sourceArray(byte[] raw, int start, int count) {
        int[] sources = new int[count]; for (int i = 0; i < count; i++) sources[i] = unsigned(raw[start + i]); return sources;
    }
    private static int unsigned(byte value) { return value & 255; }
    private static int little(byte[] raw, int start) { return unsigned(raw[start]) | (unsigned(raw[start + 1]) << 8); }
    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (JSONException error) { throw new IllegalStateException(error); }
    }
    private static final class Parsed {
        final List<Group> groups = new ArrayList<>(); final List<Stream> streams = new ArrayList<>(); final JSONArray errors = new JSONArray();
    }
    private static final class Group {
        final int configuration, id; int version = -1, totalBytes = -1, descriptorBytes;
        final Set<Integer> collection = new HashSet<>(); final Map<Integer, Entity> entities = new LinkedHashMap<>();
        Group(int configuration, int id) { this.configuration = configuration; this.id = id; }
    }
    private static final class Stream {
        final int configuration, id, alternate; int terminal = -1, formatTag = -1; boolean isochronousInput;
        Stream(int configuration, int id, int alternate) { this.configuration = configuration; this.id = id; this.alternate = alternate; }
    }
    private static final class Entity {
        final int id, subtype; int[] sources = new int[0]; int terminalType = -1, association = -1, channels = -1, controlSize;
        int selectedPin = -1, getCurBytes = -1; boolean opaque; final JSONArray featureControls = new JSONArray();
        Entity(int id, int subtype) { this.id = id; this.subtype = subtype; }
    }
    private static final class Selected {
        boolean resolved = true, opaque; final JSONArray terminals = new JSONArray();
        final Set<Integer> ids = new HashSet<>(), types = new HashSet<>(), visited = new HashSet<>();
    }
}
