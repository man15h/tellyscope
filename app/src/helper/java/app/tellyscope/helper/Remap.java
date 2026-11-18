// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.helper;

import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Remap TV remote's script. Runs on the TV as the shell user through {@code app_process}, on its
 * own after the phone has gone: Tellyscope starts it detached and talks to it through files in its
 * directory, the one argument.
 *
 * It reads the TV's remotes from {@code /dev/input} (the shell user is in group {@code input}).
 * A remote that has a mapped button is held with {@code EVIOCGRAB}, so the TV no longer sees it
 * directly: a mapped button runs its action, and every other key is passed on as the same Android
 * key, injected the way {@code input keyevent} does it. An air-mouse remote's pointer goes on through
 * a virtual mouse. Remotes without a mapped button are never held. If the TV won't take an injected
 * key, the script lets go of every remote and exits, so the remote can't be left dead.
 *
 * Files: {@code map} is the mappings ({@link KeyLayout#map}), read again whenever it changes;
 * {@code learn} appearing asks for the next button pressed, which is written to {@code learned} as
 * its name, or {@code !name} for one that may not be mapped; {@code pid} is written once it runs.
 * Deleting the directory stops it, and so does SIGTERM. Holding a remote this way, as the shell user
 * over ADB, is the idea behind Key Mapper's ADB mode (https://github.com/keymapperorg/KeyMapper);
 * no code is taken from it.
 */
public final class Remap {
    /** {@code _IOW('E', 0x90, int)}: grabs while its argument is non-zero, and Os passes a pointer. */
    private static final int EVIOCGRAB = 0x40044590;
    private static final int EV_SYN = 0, EV_KEY = 1, EV_REL = 2, EV_MSC = 4, MSC_SCAN = 4;
    private static final int REL_X = 0, REL_Y = 1, REL_WHEEL = 8;
    private static final int BTN_LEFT = 0x110, BTN_RIGHT = 0x111, BTN_MIDDLE = 0x112;
    /** How often {@code /dev/input} is looked at again, for a Bluetooth remote that woke up as a new device. */
    private static final long SCAN_MS = 2000;
    private static final int INJECT_ASYNC = 0;

    private final File dir;
    private final Object manager;
    private final Method inject;
    private final int eventSize = is64() ? 24 : 16;
    private final Map<String, Dev> devices = new HashMap<>();
    private String[] seen = new String[0];
    private Map<String, String> map = new HashMap<>();
    private long mapStamp = -1;
    private boolean learning;
    /** A refused press passed on while learning: its remote stays held until this key's release is passed on too. */
    private Dev waitUp;
    private int waitCode;
    private Uhid mouse;
    private boolean mouseRefused;

    /** One open remote. {@code held} is each key that went down: when, how many repeats, and the key passed on (0 if swallowed). */
    private static final class Dev {
        final KeyLayout.Device info;
        final KeyLayout layout;
        FileDescriptor fd;
        boolean grabbed;
        int usage = -1;
        int buttons, dx, dy, wheel;
        boolean moved;
        final Map<Integer, long[]> held = new HashMap<>();

        Dev(KeyLayout.Device info, KeyLayout layout, FileDescriptor fd) {
            this.info = info;
            this.layout = layout;
            this.fd = fd;
        }
    }

    private Remap(File dir, Object manager, Method inject) {
        this.dir = dir;
        this.manager = manager;
        this.inject = inject;
    }

    public static void main(String[] args) throws Exception {
        Object manager;
        Method inject;
        try {
            Class<?> global = Class.forName("android.hardware.input.InputManagerGlobal");
            manager = global.getDeclaredMethod("getInstance").invoke(null);
            inject = global.getMethod("injectInputEvent", InputEvent.class, int.class);
        } catch (ClassNotFoundException e) {
            Class<?> im = Class.forName("android.hardware.input.InputManager");
            manager = im.getDeclaredMethod("getInstance").invoke(null);
            inject = im.getMethod("injectInputEvent", InputEvent.class, int.class);
        }
        File dir = new File(args[0]);
        new File(dir, "remap.jar").delete();
        Remap r = new Remap(dir, manager, inject);
        r.scan();
        write(new File(dir, "pid"), android.os.Process.myPid() + "\n");
        r.run();
    }

    private void run() throws Exception {
        long nextScan = SystemClock.uptimeMillis() + SCAN_MS;
        byte[] buf = new byte[eventSize * 64];
        while (dir.isDirectory()) {
            if (SystemClock.uptimeMillis() >= nextScan) {
                scan();
                nextScan = SystemClock.uptimeMillis() + SCAN_MS;
            }
            readMap();
            boolean asked = new File(dir, "learn").exists();
            if (asked != learning) {
                learning = asked;
                regrab();
            }
            List<Dev> open = new ArrayList<>(devices.values());
            StructPollfd[] polls = new StructPollfd[open.size()];
            for (int i = 0; i < polls.length; i++) {
                polls[i] = new StructPollfd();
                polls[i].fd = open.get(i).fd;
                polls[i].events = (short) OsConstants.POLLIN;
            }
            if (polls.length == 0) {
                SystemClock.sleep(500);
                continue;
            }
            try {
                Os.poll(polls, 500);
            } catch (ErrnoException e) {
                if (e.errno == OsConstants.EINTR) continue;
                throw e;
            }
            for (int i = 0; i < polls.length; i++) {
                if (polls[i].revents == 0) continue;
                Dev d = open.get(i);
                int n;
                try {
                    n = Os.read(d.fd, buf, 0, buf.length);
                } catch (ErrnoException e) {
                    // ENODEV: the remote went away (a Bluetooth one asleep); the next scan finds it again.
                    drop(d);
                    continue;
                }
                ByteBuffer bb = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.nativeOrder());
                for (int off = 0; off + eventSize <= n; off += eventSize) {
                    bb.position(off + eventSize - 8);
                    event(d, bb.getShort() & 0xffff, bb.getShort() & 0xffff, bb.getInt());
                }
            }
        }
        for (Dev d : new ArrayList<>(devices.values())) drop(d);
    }

    /** Opens remotes that appeared and drops ones that went, when {@code /dev/input}'s list changed. */
    private void scan() throws Exception {
        String[] now = new File("/dev/input").list();
        if (now == null) now = new String[0];
        Arrays.sort(now);
        if (Arrays.equals(now, seen)) return;
        seen = now;
        Map<String, KeyLayout.Device> found = new HashMap<>();
        for (KeyLayout.Device d : KeyLayout.devices(run("dumpsys", "input"))) if (d.remote()) found.put(d.path, d);
        for (Iterator<Dev> it = devices.values().iterator(); it.hasNext(); ) {
            Dev d = it.next();
            KeyLayout.Device f = found.get(d.info.path);
            if (f == null || !f.name.equals(d.info.name)) {
                close(d);
                it.remove();
            }
        }
        for (KeyLayout.Device f : found.values()) {
            if (devices.containsKey(f.path)) continue;
            try {
                KeyLayout layout = KeyLayout.parse(read(new File(f.layout)));
                devices.put(f.path, new Dev(f, layout, Os.open(f.path, OsConstants.O_RDONLY, 0)));
            } catch (Exception e) {
                System.err.println("skip " + f.name + ": " + e);
            }
        }
        regrab();
    }

    private void readMap() throws Exception {
        File f = new File(dir, "map");
        long stamp = f.exists() ? f.lastModified() : 0;
        if (stamp == mapStamp) return;
        mapStamp = stamp;
        map = stamp == 0 ? new HashMap<>() : KeyLayout.map(read(f));
        regrab();
    }

    /** Holds each remote that has a mapped button, or while learning, any button that could be; lets go of the rest. */
    private void regrab() throws Exception {
        for (Dev d : devices.values()) {
            boolean want = d == waitUp || d.layout.names(learning ? KeyLayout.ALLOWED : map.keySet());
            if (want == d.grabbed) continue;
            if (want) {
                grab(d.fd);
            } else {
                // EVIOCGRAB can't be undone with this ioctl (it always passes a pointer), so open it again.
                Os.close(d.fd);
                d.fd = Os.open(d.info.path, OsConstants.O_RDONLY, 0);
            }
            d.grabbed = want;
            d.held.clear();
            d.usage = -1;
        }
    }

    private void event(Dev d, int type, int code, int value) throws Exception {
        if (type == EV_MSC && code == MSC_SCAN) {
            d.usage = value;
            return;
        }
        if (type == EV_SYN) {
            d.usage = -1;
            if (d.moved) pointer(d);
            return;
        }
        if (type == EV_REL && d.grabbed) {
            if (code == REL_X) d.dx += value;
            else if (code == REL_Y) d.dy += value;
            else if (code == REL_WHEEL) d.wheel += value;
            d.moved = true;
            return;
        }
        if (type != EV_KEY) return;
        if (d.grabbed && (code == BTN_LEFT || code == BTN_RIGHT || code == BTN_MIDDLE)) {
            int bit = 1 << (code - BTN_LEFT);
            d.buttons = value != 0 ? d.buttons | bit : d.buttons & ~bit;
            d.moved = true;
            return;
        }
        String name = d.layout.name(code, d.usage);
        if (learning && value == 1) {
            boolean allowed = name != null && KeyLayout.ALLOWED.contains(name);
            write(new File(dir, "learned"), (allowed ? name : "!" + (name == null ? "" : name)) + "\n");
            new File(dir, "learn").delete();
            if (allowed) {
                d.held.put(code, new long[] {0, 0, 0});
                learning = false;
                regrab();
                return;
            }
            learning = false;
            // Not one that may be mapped: it still does what it always did. Letting go of the remote now would send
            // the release to the TV's own reader, which never saw the press, so the key would stay down.
            if (d.grabbed) {
                pass(d, code, name, value);
                if (d.held.containsKey(code)) {
                    waitUp = d;
                    waitCode = code;
                }
            }
            regrab();
            return;
        }
        if (!d.grabbed) return;
        String action = name == null ? null : map.get(name);
        if (action != null) {
            // A mapped button runs once, on its press; its repeats and release go nowhere.
            if (value == 1) act(action);
            return;
        }
        long[] h = d.held.get(code);
        if (h != null && h[2] == 0) {
            // The button just learned: its repeats and release go nowhere either.
            if (value == 0) d.held.remove(code);
            return;
        }
        pass(d, code, name, value);
        if (value == 0 && d == waitUp && code == waitCode) {
            waitUp = null;
            regrab();
        }
    }

    /** Passes a key on as its Android key: down, each repeat (a long press), up. */
    private void pass(Dev d, int code, String name, int value) throws Exception {
        int key = name == null ? 0 : KeyEvent.keyCodeFromString("KEYCODE_" + name);
        if (key <= 0) return;
        long now = SystemClock.uptimeMillis();
        long[] h = d.held.get(code);
        if (value == 1 || h == null) {
            // A repeat or release of a press from before the remote was held: the TV has that one already.
            if (value != 1) return;
            h = new long[] {now, 0, key};
            d.held.put(code, h);
        } else if (value == 2) {
            h[1]++;
        }
        int action = value == 0 ? KeyEvent.ACTION_UP : KeyEvent.ACTION_DOWN;
        int flags = value == 2 && h[1] == 1 ? KeyEvent.FLAG_LONG_PRESS : 0;
        KeyEvent e = new KeyEvent(h[0], now, action, (int) h[2], value == 0 ? 0 : (int) h[1], 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, code, flags, InputDevice.SOURCE_KEYBOARD);
        if (value == 0) d.held.remove(code);
        boolean ok;
        try {
            ok = (Boolean) inject.invoke(manager, e, INJECT_ASYNC);
        } catch (Exception ex) {
            ok = false;
        }
        if (!ok) {
            // The TV won't take keys from the shell: a held remote would be dead, so let everything go.
            System.err.println("inject refused, exiting");
            for (Dev x : new ArrayList<>(devices.values())) drop(x);
            new File(dir, "pid").delete();
            System.exit(2);
        }
    }

    /** An air-mouse remote's movement and clicks since the last report, through a virtual mouse. */
    private void pointer(Dev d) {
        d.moved = false;
        int dx = d.dx, dy = d.dy, wheel = d.wheel;
        d.dx = d.dy = d.wheel = 0;
        if (mouseRefused) return;
        try {
            if (mouse == null) mouse = new Uhid("Tellyscope remote pointer", Uhid.MOUSE);
            do {
                int x = clamp(dx), y = clamp(dy), w = clamp(wheel);
                mouse.send(new byte[] {(byte) d.buttons, (byte) x, (byte) y, (byte) w});
                dx -= x;
                dy -= y;
                wheel -= w;
            } while (dx != 0 || dy != 0 || wheel != 0);
        } catch (Exception e) {
            // No /dev/uhid for the shell on this TV: the remote's keys still work, its pointer doesn't.
            mouseRefused = true;
            System.err.println("no virtual mouse: " + e);
        }
    }

    private static int clamp(int v) {
        return Math.max(-127, Math.min(127, v));
    }

    /** {@code -} does nothing; an activity is started, {@code @package} opened by its launcher entry. */
    private static void act(String action) {
        if (action.equals("-")) return;
        String[] cmd = action.startsWith("@")
            ? new String[] {"monkey", "-p", action.substring(1), "-c", "android.intent.category.LEANBACK_LAUNCHER", "--pct-syskeys", "0", "1"}
            : new String[] {"am", "start", "-n", action};
        try {
            final Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            // Reaped off the loop, so the remote stays quick while the app starts.
            new Thread() {
                @Override public void run() {
                    try {
                        drain(p.getInputStream());
                        p.waitFor();
                    } catch (Exception ignored) {
                    }
                }
            }.start();
        } catch (Exception e) {
            System.err.println("couldn't open " + action + ": " + e);
        }
    }

    private void drop(Dev d) {
        if (d == waitUp) waitUp = null;
        close(d);
        devices.remove(d.info.path);
        // Seen again on the next scan, even if the list looks the same.
        seen = new String[0];
    }

    private static void close(Dev d) {
        try {
            Os.close(d.fd);
        } catch (Exception ignored) {
        }
    }

    /** Os.ioctlInt takes (fd, cmd) on new builds and (fd, cmd, MutableInt) on older ones. */
    private static void grab(FileDescriptor fd) throws Exception {
        for (Method m : Os.class.getMethods()) {
            if (!m.getName().equals("ioctlInt")) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2) {
                m.invoke(null, fd, EVIOCGRAB);
                return;
            }
            if (p.length == 3) {
                m.invoke(null, fd, EVIOCGRAB, p[2].getConstructor(int.class).newInstance(1));
                return;
            }
        }
        throw new NoSuchMethodException("Os.ioctlInt");
    }

    private static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = drain(p.getInputStream());
        p.waitFor();
        return out;
    }

    private static String drain(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        for (int n; (n = in.read(chunk)) > 0; ) b.write(chunk, 0, n);
        return b.toString("UTF-8");
    }

    private static String read(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            return drain(in);
        }
    }

    private static void write(File f, String text) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new java.io.IOException("can't write " + f);
    }

    private static boolean is64() {
        try {
            return (Boolean) android.os.Process.class.getMethod("is64Bit").invoke(null);
        } catch (Exception e) {
            return System.getProperty("os.arch", "").contains("64");
        }
    }
}
