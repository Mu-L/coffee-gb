package eu.rekawek.coffeegb.memory.cart.battery;

import eu.rekawek.coffeegb.memento.Memento;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

public class FileBattery implements Battery {

    private final File saveFile;

    private final byte[] clockBuffer;

    private final byte[] ramBuffer;

    private boolean isClockPresent;

    private boolean isDirty;

    public FileBattery(File saveFile, int ramSize) {
        this.saveFile = saveFile;
        this.clockBuffer = new byte[11 * 4];
        this.ramBuffer = new byte[ramSize];
    }

    @Override
    public void loadRam(int[] ram) {
        loadRamWithClock(ram, null);
    }

    @Override
    public void saveRam(int[] ram) {
        saveRamWithClock(ram, null);
    }

    @Override
    public void loadRamWithClock(int[] ram, long[] clockData) {
        if (!saveFile.exists()) {
            return;
        }
        long saveLength = saveFile.length();
        saveLength = saveLength - (saveLength % 0x2000);
        try (InputStream is = Files.newInputStream(saveFile.toPath())) {
            loadRam(ram, is, saveLength);
            if (clockData != null) {
                loadClock(clockData, is);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void saveRamWithClock(int[] ram, long[] clockData) {
        doSaveRam(ram);
        if (clockData != null) {
            doSaveClock(clockData);
            isClockPresent = true;
        }
        isDirty = true;
    }

    public void flush() {
        if (!isDirty) {
            return;
        }
        try (OutputStream os = Files.newOutputStream(saveFile.toPath())) {
            os.write(ramBuffer);
            if (isClockPresent) {
                os.write(clockBuffer);
            }
            isClockPresent = false;
            isDirty = false;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void loadClock(long[] clockData, InputStream is) throws IOException {
        byte[] byteBuff = new byte[4 * clockData.length];
        IOUtils.read(is, byteBuff);
        ByteBuffer buff = ByteBuffer.wrap(byteBuff);
        buff.order(ByteOrder.LITTLE_ENDIAN);
        int i = 0;
        while (buff.hasRemaining()) {
            clockData[i++] = buff.getInt();
        }
    }

    private void loadRam(int[] ram, InputStream is, long length) throws IOException {
        byte[] buffer = new byte[ram.length];
        IOUtils.read(is, buffer, 0, Math.min((int) length, ram.length));
        for (int i = 0; i < ram.length; i++) {
            ram[i] = buffer[i] & 0xff;
        }
    }

    private void doSaveClock(long[] clockData) {
        ByteBuffer buff = ByteBuffer.wrap(clockBuffer);
        buff.order(ByteOrder.LITTLE_ENDIAN);
        for (long d : clockData) {
            buff.putInt((int) d);
        }
    }

    private void doSaveRam(int[] ram) {
        for (int i = 0; i < ram.length; i++) {
            ramBuffer[i] = (byte) (ram[i]);
        }
    }

    @Override
    public Memento<Battery> saveToMemento() {
        return new FileBatteryMemento(clockBuffer.clone(), ramBuffer.clone(), isClockPresent, isDirty);
    }

    @Override
    public void restoreFromMemento(Memento<Battery> memento) {
        if (!(memento instanceof FileBatteryMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        if (this.clockBuffer.length != mem.clockBuffer.length) {
            throw new IllegalArgumentException("Memento clockBuffer length doesn't match");
        }
        if (this.ramBuffer.length != mem.ramBuffer.length) {
            throw new IllegalArgumentException("Memento ramBuffer length doesn't match");
        }
        System.arraycopy(mem.clockBuffer, 0, this.clockBuffer, 0, this.clockBuffer.length);
        System.arraycopy(mem.ramBuffer, 0, this.ramBuffer, 0, this.ramBuffer.length);
        this.isClockPresent = mem.isClockPresent;
        this.isDirty = mem.isDirty;
    }

    private record FileBatteryMemento(byte[] clockBuffer, byte[] ramBuffer, boolean isClockPresent,
                                      boolean isDirty) implements Memento<Battery> {
    }
}
