package br.com.sistema51.a34.usb;

import java.io.IOException;

/** Only restore a journal when hardware still matches the saved or temporary source. */
final class UsbSelectorRecovery {
    private UsbSelectorRecovery() {}
    static int originalPin(int saved, int temporary, int current) throws IOException {
        if (saved < 1 || saved > 4 || temporary != 3)
            throw new IOException("Registro de recuperação USB inválido.");
        if (current != saved && current != temporary)
            throw new IOException("Entrada USB mudou desde a falha; recuperação automática recusada.");
        return saved;
    }
}
