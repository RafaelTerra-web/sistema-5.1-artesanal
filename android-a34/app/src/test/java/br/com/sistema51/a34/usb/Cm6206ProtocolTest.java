package br.com.sistema51.a34.usb;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Wire-format tests; no USB hardware or Android service is accessed. */
public final class Cm6206ProtocolTest {
    @Test public void sixReadRequestsUseFourPayloadBytesAndNeverWriteOpcode() {
        for (int register = 0; register < 6; register++) {
            assertArrayEquals(new byte[] {0x30, 0, 0, (byte) register}, Cm6206HidControl.readCommand(register));
        }
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsNegativeRegister() {
        Cm6206HidControl.readCommand(-1);
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsRegisterBeyondHardwareRange() {
        Cm6206HidControl.readCommand(6);
    }

    @Test public void capturesObservedWindowsRegisterValuesAsLittleEndianPayloads() {
        int[] values = {0x2000, 0x3002, 0x6004, 0x167f, 0, 0x3000};
        for (int value : values) {
            byte[] reply = {(byte) 0x20, (byte) value, (byte) (value >> 8)};
            assertTrue(Cm6206HidControl.isRegisterReply(reply, 3));
            assertEquals(value, Cm6206HidControl.registerValue(reply, 3));
        }
    }

    @Test public void recognizesHeaderFlagsWithoutInterpretingTheirMeaning() {
        assertTrue(Cm6206HidControl.isRegisterReply(new byte[] {0x3f, (byte) 0xff, (byte) 0xff}, 3));
        assertEquals(0xffff, Cm6206HidControl.registerValue(new byte[] {0x3f, (byte) 0xff, (byte) 0xff}, 3));
    }

    @Test public void buttonAndMalformedReportsCannotBecomeRegisterValues() {
        assertFalse(Cm6206HidControl.isRegisterReply(new byte[] {0, 2, 3}, 3));
        assertFalse(Cm6206HidControl.isRegisterReply(new byte[] {0x40, 2, 3}, 3));
        assertFalse(Cm6206HidControl.isRegisterReply(new byte[] {0x20, 2}, 2));
        assertFalse(Cm6206HidControl.isRegisterReply(new byte[] {0x20, 2}, 3));
        assertFalse(Cm6206HidControl.isRegisterReply(null, 3));
        // Windows adds ID zero in its API buffer. It is not an Android wire byte.
        assertFalse(Cm6206HidControl.isRegisterReply(new byte[] {0, 0x20, 2, 3}, 4));
    }

    @Test(expected = IllegalArgumentException.class) public void refusesToDecodeAnEventReport() {
        Cm6206HidControl.registerValue(new byte[] {0, 1, 2}, 3);
    }
}
