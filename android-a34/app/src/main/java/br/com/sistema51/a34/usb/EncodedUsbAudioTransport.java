package br.com.sistema51.a34.usb;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;

import java.io.IOException;

/**
 * Raw USB capture contract. Android AudioRecord yields PCM and cannot establish
 * that S/PDIF AC-3/IEC61937 bursts survive a particular USB interface. The CM6206
 * implementation uses the physically measured S/PDIF source and duplex endpoints;
 * callers still validate every AC-3 burst and track runtime continuity errors.
 */
public interface EncodedUsbAudioTransport extends AutoCloseable {
    enum Validation { NOT_VALIDATED, VALIDATED_PCM_ONLY, VALIDATED_IEC61937_AC3 }

    Validation validation();
    String validationEvidence();

    /** Open only after explicit USB permission and a successful validation campaign. */
    void open(UsbDevice device, UsbDeviceConnection connection) throws IOException;

    /**
     * Read an ordered byte stream into the caller's reusable buffer.
     * Return positive bytes, zero for a bounded timeout, or -1 for end of stream.
     * Implementations must unblock reads when close() is called.
     */
    int read(byte[] destination, int offset, int length, int timeoutMillis) throws IOException;

    @Override void close() throws IOException;
}
