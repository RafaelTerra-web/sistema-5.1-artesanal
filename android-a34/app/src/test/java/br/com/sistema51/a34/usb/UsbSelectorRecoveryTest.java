package br.com.sistema51.a34.usb;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public final class UsbSelectorRecoveryTest {
    @Test public void reconnectWithOriginalSourceCanCompleteRecovery() throws Exception {
        assertEquals(1, UsbSelectorRecovery.originalPin(1, 3, 1));
        assertEquals(1, UsbSelectorRecovery.originalPin(1, 3, 3));
        assertEquals(3, UsbSelectorRecovery.originalPin(3, 3, 3));
    }
    @Test public void unrelatedSourceIsNeverOverwritten() {
        try { UsbSelectorRecovery.originalPin(1, 3, 2); fail("Unrelated source accepted"); }
        catch (IOException expected) { }
    }
    @Test public void malformedJournalIsNeverApplied() {
        for (int[] value : new int[][]{{0,3,3},{5,3,3},{1,2,2}}) {
            try { UsbSelectorRecovery.originalPin(value[0],value[1],value[2]); fail("Invalid journal accepted"); }
            catch (IOException expected) { }
        }
    }
}
