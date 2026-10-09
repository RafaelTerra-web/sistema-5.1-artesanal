package br.com.sistema51.a34.usb;

import java.io.IOException;

/** Only REG2.DRIVERON is owned. Never changes channel mutes, SPDIF or EEPROM. */
public final class Cm6206AnalogDriver implements AutoCloseable {
    public static final int REGISTER=2, MASK=0x8000;
    public interface Registers {
        int read(int register) throws IOException;
        void writeDriver(int value) throws IOException;
        void journal(int original) throws IOException;
        void clearJournal() throws IOException;
    }
    private final Registers access;
    private final int original;
    private boolean writeAttempted, closed;
    public Cm6206AnalogDriver(Registers access) throws IOException {
        this.access=access; original=access.read(REGISTER);
    }
    public synchronized void enable() throws IOException {
        if(closed)throw new IOException("Sessão analógica encerrada.");
        int current=access.read(REGISTER);
        if((current&MASK)==0) {
            access.journal(original); // Durable restoration baseline before a write.
            writeAttempted=true;
            access.writeDriver(current|MASK);
        }
        if((access.read(REGISTER)&MASK)==0)throw new IOException("DRIVERON não foi confirmado.");
    }
    @Override public synchronized void close() throws IOException {
        if(closed)return;
        if(writeAttempted) {
            int current=access.read(REGISTER);
            int restored=(current&~MASK)|(original&MASK);
            if(current!=restored)access.writeDriver(restored);
            if((access.read(REGISTER)&MASK)!=(original&MASK))throw new IOException("Restauração de DRIVERON não confirmada.");
            access.clearJournal();
        }
        closed=true;
    }
    public int getOriginal() { return original; }
}
