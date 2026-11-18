// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.helper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What {@link Remap} reads before it touches a remote: the TV's input devices from
 * {@code dumpsys input}, and each one's key layout ({@code .kl}), which turns the kernel's key codes
 * into Android's key names. Pure text parsing, so the app's unit tests run it too.
 */
public final class KeyLayout {
    /**
     * The only buttons a mapping may take: the four colour keys, the numbered app buttons
     * (Sony's and Google's streaming buttons send {@code BUTTON_n}), {@code AVR_POWER} (code 181, one
     * remote's Netflix button) and Fire TV's app buttons. Power, volume, the D-pad, OK, Back, Home and
     * the rest stay with the TV, so a mapping can never take away the way out.
     */
    public static final Set<String> ALLOWED = Collections.unmodifiableSet(new HashSet<>(names()));

    /** InputDeviceClass bits from Android's EventHub, as {@code dumpsys input} prints them. */
    static final int KEYBOARD = 0x1;
    static final int TOUCH = 0x4;
    static final int CURSOR = 0x8;
    static final int TOUCH_MT = 0x10;
    static final int GAMEPAD = 0x40;
    static final int VIRTUAL = 0x40000000;

    /** One input device: its {@code /dev/input} path, name, classes and key layout file. */
    public static final class Device {
        public final String path;
        public final String name;
        public final int classes;
        public final String layout;

        Device(String path, String name, int classes, String layout) {
            this.path = path;
            this.name = name;
            this.classes = classes;
            this.layout = layout;
        }

        /**
         * A remote, or something that sends keys like one. Gamepads, touch screens, Android's own
         * virtual keyboard and Tellyscope's own virtual devices are left alone, as is anything with
         * no key layout to read.
         */
        public boolean remote() {
            return path.startsWith("/dev/input/event")
                && !layout.isEmpty()
                && (classes & KEYBOARD) != 0
                && (classes & (GAMEPAD | TOUCH | TOUCH_MT | VIRTUAL)) == 0
                && !name.startsWith("Tellyscope");
        }

        /** Moves a pointer too (an air-mouse remote): {@link Remap} carries its movement over while it holds it. */
        public boolean pointer() {
            return (classes & CURSOR) != 0;
        }
    }

    /** The kernel's key codes and HID usages a layout names, each to its Android key name. */
    public final Map<Integer, String> keys;
    public final Map<Integer, String> usages;

    KeyLayout(Map<Integer, String> keys, Map<Integer, String> usages) {
        this.keys = keys;
        this.usages = usages;
    }

    private static List<String> names() {
        List<String> n = new ArrayList<>(Arrays.asList("PROG_RED", "PROG_GREEN", "PROG_YELLOW", "PROG_BLUE", "AVR_POWER"));
        for (int i = 1; i <= 16; i++) n.add("BUTTON_" + i);
        for (int i = 1; i <= 4; i++) n.add("APP_" + i);
        return n;
    }

    /**
     * The Android key name for a press: by its HID usage first, as Android's EventHub does when the
     * device reported one ({@code usage} is -1 when it didn't), else by its key code. Null when the
     * layout names neither, so the TV ignores that key too.
     */
    public String name(int code, int usage) {
        String n = usage >= 0 ? usages.get(usage) : null;
        return n != null ? n : keys.get(code);
    }

    /** Whether any key this layout names is in {@code names}. */
    public boolean names(Set<String> names) {
        for (String n : keys.values()) if (names.contains(n)) return true;
        for (String n : usages.values()) if (names.contains(n)) return true;
        return false;
    }

    /**
     * A {@code .kl} file's key lines: {@code key 398 PROG_RED} and {@code key usage 0x0c0224 BACK};
     * a code is decimal or {@code 0x} hex, and flags after the name are ignored. Axis, LED and
     * sensor lines and comments are skipped.
     */
    public static KeyLayout parse(String text) {
        Map<Integer, String> keys = new HashMap<>();
        Map<Integer, String> usages = new HashMap<>();
        for (String raw : text.split("\n")) {
            int hash = raw.indexOf('#');
            String line = (hash >= 0 ? raw.substring(0, hash) : raw).trim();
            String[] t = line.split("\\s+");
            if (t.length < 3 || !t[0].equals("key")) continue;
            boolean usage = t[1].equals("usage");
            if (usage && t.length < 4) continue;
            Integer code = number(usage ? t[2] : t[1]);
            if (code == null) continue;
            (usage ? usages : keys).put(code, usage ? t[3] : t[2]);
        }
        return new KeyLayout(keys, usages);
    }

    /**
     * The devices under {@code Event Hub State}'s {@code Devices:}: a {@code 8: XGIMI RC} line opens
     * each one, then its {@code Classes:}, {@code Path:} and {@code KeyLayoutFile:}. Classes are a hex
     * number up to Android 12 and names joined by {@code |} from 13.
     */
    public static List<Device> devices(String dumpsys) {
        List<Device> out = new ArrayList<>();
        boolean inDevices = false;
        String name = null, path = "", layout = "";
        int classes = 0;
        int indent = -1;
        for (String raw : dumpsys.split("\n")) {
            String line = raw.replace("\r", "");
            String trimmed = line.trim();
            int lead = line.length() - line.replaceAll("^\\s+", "").length();
            if (!inDevices) {
                if (trimmed.equals("Devices:")) inDevices = true;
                continue;
            }
            if (trimmed.isEmpty()) continue;
            if (indent < 0) indent = lead;
            if (lead < indent) break; // past the device list
            if (lead == indent) {
                if (name != null) out.add(new Device(path, name, classes, layout));
                int colon = trimmed.indexOf(": ");
                if (colon < 0 || !trimmed.substring(0, colon).matches("-?\\d+")) {
                    name = null;
                    break;
                }
                name = trimmed.substring(colon + 2).trim();
                path = "";
                layout = "";
                classes = 0;
                continue;
            }
            if (name == null) continue;
            if (trimmed.startsWith("Path:")) path = value(trimmed);
            else if (trimmed.startsWith("KeyLayoutFile:")) layout = value(trimmed);
            else if (trimmed.startsWith("Classes:")) classes = classes(value(trimmed));
        }
        if (name != null) out.add(new Device(path, name, classes, layout));
        return out;
    }

    private static String value(String line) {
        return line.substring(line.indexOf(':') + 1).trim();
    }

    static int classes(String v) {
        if (v.startsWith("0x")) {
            try {
                return (int) Long.parseLong(v.substring(2), 16);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        int bits = 0;
        for (String n : v.split("\\|")) {
            switch (n.trim()) {
                case "KEYBOARD": bits |= KEYBOARD; break;
                case "TOUCH": bits |= TOUCH; break;
                case "CURSOR": bits |= CURSOR; break;
                case "TOUCH_MT": bits |= TOUCH_MT; break;
                case "GAMEPAD": bits |= GAMEPAD; break;
                case "VIRTUAL": bits |= VIRTUAL; break;
                default: break;
            }
        }
        return bits;
    }

    private static Integer number(String s) {
        try {
            return s.startsWith("0x") || s.startsWith("0X") ? Integer.parseInt(s.substring(2), 16) : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * {@code map}'s lines, {@code NAME\tACTION}: an action is {@code -} for nothing, an activity
     * ({@code package/class}) to start, or {@code @package} to open by its launcher entry. Names off
     * {@link #ALLOWED} and malformed actions are dropped, whatever the phone wrote.
     */
    public static Map<String, String> map(String text) {
        Map<String, String> out = new HashMap<>();
        for (String line : text.split("\n")) {
            String[] t = line.trim().split("\t");
            if (t.length != 2 || !ALLOWED.contains(t[0])) continue;
            String a = t[1];
            if (a.equals("-") || a.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+")
                || a.matches("@[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) {
                out.put(t[0], a);
            }
        }
        return out;
    }
}
