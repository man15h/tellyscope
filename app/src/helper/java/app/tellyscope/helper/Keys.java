// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.helper;

import android.content.ClipData;
import android.app.UiAutomation;
import android.os.Build;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * The remote's key sender. Runs on the TV as the shell user, through {@code app_process}, and stays
 * up for as long as the remote is open: {@code input keyevent} starts a fresh VM for every key,
 * which is most of a second on a slow TV. Reads one Android key code per line on stdin, injects a
 * press and release the way {@code input} does, and answers {@code 1} when the TV took both or
 * {@code 0} when it didn't. Prints {@code ready} first. Only key codes are read, so nothing typed
 * reaches a shell. The argument is the jar's own path, deleted once the helper is up; it exits at
 * the end of stdin. A line starting with {@code t} is text instead: the rest is typed the way
 * {@code input text} types it, one key press per character, or pasted when it holds a character
 * the keymap has no key for (Cyrillic, Arabic); {@code 0} when the paste didn't land. {@code v}
 * and a signed count moves the media volume that many steps in one set, with the TV's volume bar,
 * or by one key press where a set can't move it; {@code 0} means neither worked, and the caller
 * presses the keys instead. {@code p0} turns the TV's picture off and {@code p2} back on, through
 * SurfaceFlinger: the TV stays awake, so ADB stays connected, and the picture stays off after the
 * helper exits.
 */
public final class Keys {
    /** InputManager's INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH: answers once the focused app is done with it. */
    private static final int WAIT_FOR_FINISH = 2;
    /** AudioManager.STREAM_MUSIC and FLAG_SHOW_UI. */
    private static final int STREAM_MUSIC = 3;
    private static final int FLAG_SHOW_UI = 1;

    public static void main(String[] args) throws Exception {
        Object manager;
        Method inject;
        try {
            // Android 14 moved injection to InputManagerGlobal; older builds have it on InputManager.
            Class<?> global = Class.forName("android.hardware.input.InputManagerGlobal");
            manager = global.getDeclaredMethod("getInstance").invoke(null);
            inject = global.getMethod("injectInputEvent", InputEvent.class, int.class);
        } catch (ClassNotFoundException e) {
            Class<?> im = Class.forName("android.hardware.input.InputManager");
            manager = im.getDeclaredMethod("getInstance").invoke(null);
            inject = im.getMethod("injectInputEvent", InputEvent.class, int.class);
        }
        if (args.length == 1) new File(args[0]).delete();

        PrintStream out = new PrintStream(System.out, false, "UTF-8");
        out.print("ready\n");
        out.flush();
        KeyCharacterMap keymap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        for (String line; (line = in.readLine()) != null; ) {
            boolean ok;
            try {
                if (line.startsWith("p")) {
                    out.print(power(Integer.parseInt(line.substring(1).trim())) ? "1\n" : "0\n");
                    out.flush();
                    continue;
                }
                if (line.startsWith("v")) {
                    out.print(volume(manager, inject, Integer.parseInt(line.substring(1).trim())) ? "1\n" : "0\n");
                    out.flush();
                    continue;
                }
                if (line.startsWith("t")) {
                    out.print(type(manager, inject, keymap, line.substring(1)) ? "1\n" : "0\n");
                    out.flush();
                    continue;
                }
                int code = Integer.parseInt(line.trim());
                long now = SystemClock.uptimeMillis();
                ok = send(manager, inject, now, KeyEvent.ACTION_DOWN, code, 0)
                    && send(manager, inject, now, KeyEvent.ACTION_UP, code, 0);
            } catch (Throwable t) {
                ok = false;
            }
            out.print(ok ? "1\n" : "0\n");
            out.flush();
        }
    }

    /**
     * SurfaceControl's display power mode: 0 is off, 2 is on. Android 10 to 13 name the display by
     * getInternalDisplayToken, older builds by getBuiltInDisplay(0). Android 14 moved both to a
     * class the shell's VM can't load, so there it answers 0.
     */
    private static boolean power(int mode) {
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            Object display;
            try {
                display = sc.getMethod("getInternalDisplayToken").invoke(null);
            } catch (NoSuchMethodException e) {
                display = sc.getMethod("getBuiltInDisplay", int.class).invoke(null, 0);
            }
            if (display == null) return false;
            sc.getMethod("setDisplayPowerMode", IBinder.class, int.class).invoke(null, display, mode);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Sets the media volume {@code delta} steps from where it is, kept inside the range, through
     * IAudioService as the shell, and reads it back. Where the set can't move it, one Volume key
     * goes instead: already at the end (the key shows the bar), or an output whose volume is held
     * at full. That is a fixed output, or a box with HDMI-CEC, where AOSP drops the set and turns
     * each key into one CEC press to the TV, so a tap is one step of the TV's own volume. False when
     * the volume can't be read or set at all; the caller then presses the keys.
     */
    private static boolean volume(Object manager, Method inject, int delta) throws Exception {
        boolean moved;
        try {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "audio");
            if (binder == null) return false;
            Object audio = Class.forName("android.media.IAudioService$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
            Method get = audio.getClass().getMethod("getStreamVolume", int.class);
            int now = (Integer) get.invoke(audio, STREAM_MUSIC);
            int max = (Integer) audio.getClass().getMethod("getStreamMaxVolume", int.class).invoke(audio, STREAM_MUSIC);
            int target = Math.max(0, Math.min(max, now + delta));
            if (target == now) {
                moved = false;
            } else {
                if (!setVolume(audio, target)) return false;
                moved = (Integer) get.invoke(audio, STREAM_MUSIC) != now;
            }
        } catch (Throwable t) {
            return false;
        }
        if (moved) return true;
        int code = delta > 0 ? KeyEvent.KEYCODE_VOLUME_UP : KeyEvent.KEYCODE_VOLUME_DOWN;
        long down = SystemClock.uptimeMillis();
        return send(manager, inject, down, KeyEvent.ACTION_DOWN, code, 0)
            && send(manager, inject, down, KeyEvent.ACTION_UP, code, 0);
    }

    /**
     * IAudioService.setStreamVolume(stream, index, flags, package), which Android 13 also has as
     * setStreamVolumeWithAttribution with a tag after the package. Called as {@code com.android.shell}
     * with a null tag, and FLAG_SHOW_UI so the TV shows its volume bar.
     */
    private static boolean setVolume(Object audio, int index) throws Exception {
        for (Method m : audio.getClass().getMethods()) {
            if (!m.getName().equals("setStreamVolume") && !m.getName().equals("setStreamVolumeWithAttribution")) continue;
            Class<?>[] types = m.getParameterTypes();
            if (types.length < 4 || types[0] != int.class || types[1] != int.class || types[2] != int.class) continue;
            Object[] args = new Object[types.length];
            args[0] = STREAM_MUSIC;
            args[1] = index;
            args[2] = FLAG_SHOW_UI;
            boolean ok = true;
            for (int i = 3; i < types.length; i++) {
                if (types[i] != String.class) { ok = false; break; }
                args[i] = i == 3 ? "com.android.shell" : null;
            }
            if (!ok) continue;
            m.invoke(audio, args);
            return true;
        }
        return false;
    }

    /** Null from getEvents means a character the keymap has no key for: that text is pasted. */
    private static boolean type(Object manager, Method inject, KeyCharacterMap keymap, String text) {
        try {
            KeyEvent[] events = keymap.getEvents(text.toCharArray());
            if (events == null) return paste(manager, inject, text);
            for (KeyEvent e : events) {
                KeyEvent now = KeyEvent.changeTimeRepeat(e, SystemClock.uptimeMillis(), 0);
                now.setSource(InputDevice.SOURCE_KEYBOARD);
                if (!(Boolean) inject.invoke(manager, now, WAIT_FOR_FINISH)) return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Puts {@code text} on the TV's clipboard as the shell, then presses Paste in the focused box. The
     * keymap only has keys for Latin, so this is the one way to non-Latin text without an input
     * method on the TV. It replaces whatever the TV's clipboard held.
     *
     * Injecting the key only says the TV received it: an input method that eats Paste (Fire TV's
     * keyboard is the suspect), or an app that can't read the clipboard, drops it without a word. So
     * from Android 7 the focused box is read through accessibility before and after; where the paste
     * didn't change it, its text is set with the pasted part inserted at the cursor (Android 8 and
     * up, never a password box). False when there is no text box in focus or neither changed it.
     * Where accessibility can't connect (another test tool holds it), the key's answer stands.
     */
    private static boolean paste(Object manager, Method inject, String text) throws Exception {
        boolean clip;
        try {
            clip = setClip(ClipData.newPlainText(null, text));
        } catch (Throwable t) {
            // A clipboard the shell can't write still leaves setting the text.
            clip = false;
        }
        if (Build.VERSION.SDK_INT < 24) return clip && pressPaste(manager, inject);
        HandlerThread thread = new HandlerThread("a11y");
        thread.start();
        UiAutomation automation = connect(thread.getLooper());
        try {
            if (automation == null) return clip && pressPaste(manager, inject);
            AccessibilityNodeInfo box = focusedBox(automation);
            if (box == null) return false;
            String before = boxText(box);
            if (clip && pressPaste(manager, inject) && changed(box, before)) return true;
            if (Build.VERSION.SDK_INT < 26 || box.isPassword()) return false;
            int start = box.getTextSelectionStart();
            int end = box.getTextSelectionEnd();
            if (start < 0 || end < start || end > before.length()) start = end = before.length();
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                before.substring(0, start) + text + before.substring(end));
            return box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) && changed(box, before);
        } finally {
            if (automation != null) {
                try {
                    UiAutomation.class.getDeclaredMethod("disconnect").invoke(automation);
                } catch (Throwable t) {
                    // Gone with the helper at the latest.
                }
            }
            thread.quitSafely();
        }
    }

    /** Paste is its own key from Android 7; older text boxes take Ctrl+V. */
    private static boolean pressPaste(Object manager, Method inject) throws Exception {
        long now = SystemClock.uptimeMillis();
        if (Build.VERSION.SDK_INT >= 24) {
            return send(manager, inject, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PASTE, 0)
                && send(manager, inject, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PASTE, 0);
        }
        int ctrl = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        return send(manager, inject, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, ctrl)
            && send(manager, inject, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, ctrl);
    }

    /**
     * A UiAutomation the way the {@code uiautomator} command makes one, as the shell, leaving the
     * TV's own accessibility services running. Null where this build has no such constructor (or
     * another UiAutomation is already connected).
     */
    private static UiAutomation connect(Looper looper) {
        try {
            Class<?> type = Class.forName("android.app.IUiAutomationConnection");
            Constructor<?> connection = Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor();
            connection.setAccessible(true);
            Constructor<UiAutomation> make = UiAutomation.class.getDeclaredConstructor(Looper.class, type);
            make.setAccessible(true);
            UiAutomation automation = make.newInstance(looper, connection.newInstance());
            Method connect = UiAutomation.class.getDeclaredMethod("connect", int.class);
            connect.setAccessible(true);
            connect.invoke(automation, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            return automation;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The text box with input focus, or null. An app whose accessibility was off only answers once
     * it hears it is on, which is just after the connect, so this asks for up to a second.
     */
    private static AccessibilityNodeInfo focusedBox(UiAutomation automation) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            AccessibilityNodeInfo node = automation.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (node != null) return node.isEditable() ? node : null;
            Thread.sleep(50);
        }
        return null;
    }

    /** The box's text; an empty box reports its hint as its text, which isn't there to keep. */
    private static String boxText(AccessibilityNodeInfo box) {
        if (Build.VERSION.SDK_INT >= 26 && box.isShowingHintText()) return "";
        CharSequence t = box.getText();
        return t == null ? "" : t.toString();
    }

    /**
     * Whether the box's text moved off {@code before} within a second. A plain text box has changed
     * by the time the key's answer is back; a Compose one only reports it on its next frame.
     */
    private static boolean changed(AccessibilityNodeInfo box, String before) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            if (box.refresh() && !before.equals(boxText(box))) return true;
            Thread.sleep(50);
        }
        return false;
    }

    /**
     * IClipboard.setPrimaryClip, called as {@code com.android.shell} for user 0. Its arguments grew
     * over the years: (clip, package) before Android 10, then a user id, then on 13 an attribution
     * tag before it, then on 14 a device id after it. Each is filled by its type: the clip, the
     * package then a null tag, and 0 for the user and the default device.
     */
    private static boolean setClip(ClipData clip) throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
            .getMethod("getService", String.class).invoke(null, "clipboard");
        if (binder == null) return false;
        Object service = Class.forName("android.content.IClipboard$Stub")
            .getMethod("asInterface", IBinder.class).invoke(null, binder);
        for (Method m : service.getClass().getMethods()) {
            if (!m.getName().equals("setPrimaryClip")) continue;
            Class<?>[] types = m.getParameterTypes();
            Object[] args = new Object[types.length];
            boolean packageSet = false;
            for (int i = 0; i < types.length; i++) {
                if (types[i] == ClipData.class) {
                    args[i] = clip;
                } else if (types[i] == String.class) {
                    args[i] = packageSet ? null : "com.android.shell";
                    packageSet = true;
                } else if (types[i] == int.class) {
                    args[i] = 0;
                } else {
                    args = null;
                    break;
                }
            }
            if (args == null) continue;
            m.invoke(service, args);
            return true;
        }
        return false;
    }

    private static boolean send(Object manager, Method inject, long downTime, int action, int code, int meta) throws Exception {
        KeyEvent e = new KeyEvent(downTime, SystemClock.uptimeMillis(), action, code, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        return (Boolean) inject.invoke(manager, e, WAIT_FOR_FINISH);
    }
}
