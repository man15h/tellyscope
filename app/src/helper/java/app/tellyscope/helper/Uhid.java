// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.helper;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * One virtual HID device on the TV, made through {@code /dev/uhid}. Android takes it for a real
 * mouse or gamepad: it draws its own pointer for the mouse, and games list the gamepad as a
 * connected controller, which no injected event can do. The shell user may open {@code /dev/uhid}
 * from Android 10; older builds and some vendors refuse it. Each device holds its own open file, and
 * the kernel removes the device when that file closes, so a helper that exits leaves nothing behind.
 *
 * The {@code uhid_event} layout and the CREATE2, INPUT2 and DESTROY requests follow scrcpy's
 * {@code UhidManager} (https://github.com/Genymobile/scrcpy, Apache-2.0; see NOTICE).
 */
final class Uhid {
    private static final String PATH = "/dev/uhid";
    private static final int UHID_DESTROY = 1;
    private static final int UHID_CREATE2 = 11;
    private static final int UHID_INPUT2 = 12;
    private static final short BUS_VIRTUAL = 0x06;

    /** Three buttons, then relative X, Y and wheel, -127 to 127 each: a four-byte report. */
    static final byte[] MOUSE = bytes(
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, // Generic Desktop, Mouse, Application
        0x09, 0x01, 0xA1, 0x00, //   Pointer, Physical
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02, // buttons 1-3
        0x95, 0x01, 0x75, 0x05, 0x81, 0x01, // padding to the byte
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06, // X, Y, wheel
        0xC0,
        0xC0);

    /**
     * Sixteen buttons, a left stick (X and Y, -127 to 127) and a hat, 0 to 7 clockwise from up, any
     * other value centred: a five-byte report. On a Game Pad the kernel gives button 1 BTN_A, 2 BTN_B,
     * 4 BTN_X, 5 BTN_Y, 11 BTN_SELECT and 12 BTN_START, which Android's Generic.kl names the same.
     */
    static final byte[] GAMEPAD = bytes(
        0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, // Generic Desktop, Game Pad, Application
        0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x10, 0x81, 0x02, // buttons 1-16
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x02, 0x81, 0x02, // X, Y
        0x09, 0x39, 0x15, 0x00, 0x25, 0x07, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42, // hat, null when centred
        0x65, 0x00, 0x75, 0x04, 0x95, 0x01, 0x81, 0x01, // padding to the byte
        0xC0);

    private final FileDescriptor fd;

    /** Adds the device to the TV. Throws when {@code /dev/uhid} won't open or the kernel refuses it. */
    Uhid(String name, byte[] descriptor) throws Exception {
        fd = Os.open(PATH, OsConstants.O_RDWR, 0);
        try {
            write(create(name, descriptor));
        } catch (Exception e) {
            Os.close(fd);
            throw e;
        }
    }

    /** Whether the shell may open {@code /dev/uhid} here; nothing is added. */
    static boolean allowed() {
        try {
            Os.close(Os.open(PATH, OsConstants.O_RDWR, 0));
            return true;
        } catch (ErrnoException e) {
            return false;
        }
    }

    void send(byte[] report) throws Exception {
        ByteBuffer buf = ByteBuffer.allocate(6 + report.length).order(ByteOrder.nativeOrder());
        buf.putInt(UHID_INPUT2);
        buf.putShort((short) report.length);
        buf.put(report);
        write(buf.array());
    }

    /** Takes the device off the TV. Closing the file alone would too; DESTROY says so at once. */
    void close() {
        try {
            write(ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(UHID_DESTROY).array());
        } catch (Exception ignored) {
            // The close below still removes it.
        }
        try {
            Os.close(fd);
        } catch (ErrnoException ignored) {
            // Already gone.
        }
    }

    /** name[128], phys[64], uniq[64], then rd_size, bus, vendor, product, version, country and the descriptor. */
    private static byte[] create(String name, byte[] descriptor) {
        byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(280 + descriptor.length).order(ByteOrder.nativeOrder());
        buf.putInt(UHID_CREATE2);
        buf.put(utf8, 0, Math.min(utf8.length, 127));
        buf.position(4 + 256);
        buf.putShort((short) descriptor.length);
        buf.putShort(BUS_VIRTUAL);
        // No vendor or product, so Android maps the keys by Generic.kl rather than some real pad's layout.
        buf.putInt(0);
        buf.putInt(0);
        buf.putInt(0);
        buf.putInt(0);
        buf.put(descriptor);
        return buf.array();
    }

    /** Os.write also throws InterruptedIOException, so callers take any Exception. */
    private void write(byte[] event) throws Exception {
        Os.write(fd, event, 0, event.length);
    }

    private static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) b[i] = (byte) values[i];
        return b;
    }
}
