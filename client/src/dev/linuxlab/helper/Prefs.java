package dev.linuxlab.helper;

import android.content.Context;
import android.content.SharedPreferences;

/** The settings, and what was last learned about Linux while Linux Lab was open. */
final class Prefs {
    private static final String FILE = "settings";
    private static final String CURSOR_MODE = "cursor_mode";
    private static final String KEYBOARD = "keyboard";
    private static final String CHROOT = "chroot";
    private static final String MODULE_SEEN = "module_seen";
    private static final String UBUNTU = "ubuntu";
    private static final String LAST_DESKTOP = "last_desktop";

    /** Whether the Ubuntu desktop is installed, or which keyboard is chosen: not known yet, no, yes. */
    static final int UNKNOWN = -1;
    static final int NO = 0;
    static final int YES = 1;

    private Prefs() {
    }

    static boolean cursorMode(Context context) {
        return prefs(context).getBoolean(CURSOR_MODE, false);
    }

    static void setCursorMode(Context context, boolean on) {
        prefs(context).edit().putBoolean(CURSOR_MODE, on).apply();
    }

    /**
     * The screen keyboard chosen in Linux Lab's own settings, as Linux last reported it:
     * {@link #UNKNOWN}, {@link #NO} for the keyboard of Linux, {@link #YES} for the one of Android.
     */
    static int androidKeyboard(Context context) {
        return prefs(context).getInt(KEYBOARD, UNKNOWN);
    }

    static void setAndroidKeyboard(Context context, boolean android) {
        prefs(context).edit().putInt(KEYBOARD, android ? YES : NO).apply();
    }

    /** The desktop of Linux is to run in a chroot (rooted tablets): llroot is kept running for it. */
    static boolean chroot(Context context) {
        return prefs(context).getBoolean(CHROOT, false);
    }

    static void setChroot(Context context, boolean on) {
        prefs(context).edit().putBoolean(CHROOT, on).apply();
    }

    /** The touch module has answered at least once: it is installed, Linux Lab may just be closed. */
    static boolean moduleSeen(Context context) {
        return prefs(context).getBoolean(MODULE_SEEN, false);
    }

    static void setModuleSeen(Context context, boolean seen) {
        if (moduleSeen(context) != seen) {
            prefs(context).edit().putBoolean(MODULE_SEEN, seen).apply();
        }
    }

    static int ubuntu(Context context) {
        return prefs(context).getInt(UBUNTU, UNKNOWN);
    }

    static void setUbuntu(Context context, int state) {
        if (ubuntu(context) != state) {
            prefs(context).edit().putInt(UBUNTU, state).apply();
        }
    }

    static String lastDesktop(Context context) {
        return prefs(context).getString(LAST_DESKTOP, null);
    }

    static void setLastDesktop(Context context, String name) {
        if (!name.equals(lastDesktop(context))) {
            prefs(context).edit().putString(LAST_DESKTOP, name).apply();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
