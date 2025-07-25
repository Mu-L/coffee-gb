package eu.rekawek.coffeegb.integration.support;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.Gameboy;
import eu.rekawek.coffeegb.cpu.Cpu;
import eu.rekawek.coffeegb.cpu.Registers;
import eu.rekawek.coffeegb.events.EventBus;
import eu.rekawek.coffeegb.memory.cart.Cartridge;
import eu.rekawek.coffeegb.serial.SerialEndpoint;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

import static eu.rekawek.coffeegb.integration.support.RomTestUtils.isByteSequenceAtPc;

public class MooneyeTestRunner {

    private final Gameboy gb;

    private final Cpu cpu;

    private final AddressSpace mem;

    private final Registers regs;

    private final OutputStream os;

    public MooneyeTestRunner(File romFile, OutputStream os) throws IOException {
        EventBus eventBus = new EventBus();
        Cartridge.GameboyType type = Cartridge.GameboyType.AUTOMATIC;
        boolean useBootstrap = false;
        if (romFile.toString().endsWith("-C.gb") || romFile.toString().endsWith("-cgb.gb")) {
            type = Cartridge.GameboyType.FORCE_CGB;
        }
        if (romFile.getName().startsWith("boot_")) {
            useBootstrap = true;
        }
        Cartridge cart = new Cartridge(romFile, false, type, useBootstrap);
        gb = new Gameboy(cart);
        gb.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);
        System.out.println("System type: " + (cart.isGbc() ? "CGB" : "DMG"));
        System.out.println("Bootstrap: " + (cart.isUseBootstrap() ? "enabled" : "disabled"));
        cpu = gb.getCpu();
        regs = cpu.getRegisters();
        mem = gb.getAddressSpace();
        this.os = os;
    }

    public boolean runTest() throws IOException {
        int divider = 0;
        while (!isByteSequenceAtPc(gb, 0x00, 0x18, 0xfd)) { // infinite loop
            gb.tick();
            if (++divider >= (gb.getSpeedMode().getSpeedMode() == 2 ? 1 : 4)) {
                displayProgress();
                divider = 0;
            }
        }
        return regs.getA() == 0 && regs.getB() == 3 && regs.getC() == 5 && regs.getD() == 8 && regs.getE() == 13 && regs.getH() == 21 && regs.getL() == 34;
    }

    private void displayProgress() throws IOException {
        if (cpu.getState() == Cpu.State.OPCODE && mem.getByte(regs.getPC()) == 0x22 && regs.getHL() >= 0x9800 && regs.getHL() < 0x9c00) {
            if (regs.getA() != 0) {
                os.write(regs.getA());
            }
        } else if (isByteSequenceAtPc(gb, 0x7d, 0xe6, 0x1f, 0xee, 0x1f)) {
            os.write('\n');
        }
    }
}