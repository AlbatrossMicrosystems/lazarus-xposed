package io.github.albatrossmicrosystems.lazarus;

/** Names, defaults and limits shared by the system_server hook and the app UI. */
final class Keys {
    private Keys() {}

    static final String PKG     = "io.github.albatrossmicrosystems.lazarus";
    static final String VERSION = "2.2";

    // signature permission: only apps signed with the Lazarus key may control the hook
    static final String PERM        = PKG + ".permission.CONTROL";
    static final String ACTION_CMD  = PKG + ".action.COMMAND";
    static final String ACTION_SET  = PKG + ".action.SET";
    static final String EXTRA_CMD   = "cmd";
    static final String EXTRA_KEY   = "key";
    static final String EXTRA_VALUE = "value";

    // Settings.Global keys (readable by everyone, written by system_server or adb shell)
    static final String CMD       = "lazarus_cmd";        // adb: enter | exit | reboot | shutdown
    static final String STATE     = "lazarus_state";      // 1 = fake-off
    static final String SAVED_ZEN = "lazarus_saved_zen";  // DND mode to restore (-1 = untouched)
    static final String STATUS    = "lazarus_status";     // "active;<version>;<hooks ok>/<total>;<boot wall clock ms>"
    static final String BOOTANIM  = "lazarus_bootanim";   // ok | failed  (last fake-reboot attempt)
    static final String CFG       = "lazarus_cfg_";
    static final String ORIG      = "lazarus_orig_";
    static final String ORIG_NONE = "~";                  // original setting did not exist

    // ---- integer / boolean options: name, default, min, max
    static final String C_ENABLED        = "enabled";
    static final String C_HOLD_OFF       = "hold_off_ms";
    static final String C_FAKE_REBOOT    = "fake_reboot";
    static final String C_HOLD_REBOOT    = "hold_reboot_ms";
    static final String C_BOOT_ANIM      = "boot_anim_ms";
    static final String C_OWNER_EXIT     = "owner_exit";
    static final String C_HOLD_EXIT      = "hold_exit_ms";
    static final String C_BLOCK_SHUTDOWN = "block_shutdown";
    static final String C_SILENCE        = "silence";
    static final String C_NO_VIBRATE     = "block_vibration";
    static final String C_M_SOS          = "manage_sos";
    static final String C_M_CAMERA       = "manage_camera_gesture";
    static final String C_M_LOCK_MENU    = "manage_lockscreen_power_menu";
    static final String C_M_QS_MENU      = "manage_qs_power_menu";
    // ---- string option
    static final String C_DARK_PKGS      = "dark_wake_pkgs";
    static final String DEF_DARK_PKGS    = "de.nulide.findmydevice";

    static final Object[][] INTS = {
        { C_ENABLED,        1,     0,     1 },
        { C_HOLD_OFF,       1500,  500,   5000 },
        { C_FAKE_REBOOT,    1,     0,     1 },
        { C_HOLD_REBOOT,    3000,  1000,  6000 },
        { C_BOOT_ANIM,      15500, 3000,  30000 },
        { C_OWNER_EXIT,     1,     0,     1 },
        { C_HOLD_EXIT,      3000,  1000,  10000 },
        { C_BLOCK_SHUTDOWN, 1,     0,     1 },
        { C_SILENCE,        1,     0,     1 },
        { C_NO_VIBRATE,     1,     0,     1 },
        { C_M_SOS,          1,     0,     1 },
        { C_M_CAMERA,       1,     0,     1 },
        { C_M_LOCK_MENU,    1,     0,     1 },
        { C_M_QS_MENU,      1,     0,     1 },
    };

    static Object[] spec(String name) {
        for (Object[] s : INTS) if (s[0].equals(name)) return s;
        return null;
    }
    static int def(String name) { Object[] s = spec(name); return s == null ? 0 : (Integer) s[1]; }
    static int clamp(String name, int v) {
        Object[] s = spec(name);
        if (s == null) return v;
        return Math.max((Integer) s[2], Math.min((Integer) s[3], v));
    }
    static boolean isKnown(String name) { return spec(name) != null || C_DARK_PKGS.equals(name); }

    /**
     * System settings Lazarus changes while enabled, with the value it needs.
     * {option that controls it (null = always while enabled), table, key, wanted value}
     * Originals are recorded in Settings.Global lazarus_orig_<table>_<key> and restored when the
     * option or the whole module is turned off, or the app is uninstalled.
     */
    static final String[][] MANAGED = {
        // Android 16 only wakes from screen-off on power-DOWN when a long-press action exists,
        // so this must not be 0. Lazarus neutralises the long-press menu itself.
        { null,          "global", "power_button_long_press",                   "1" },
        { C_M_SOS,       "secure", "emergency_gesture_enabled",                 "0" },
        { C_M_CAMERA,    "secure", "camera_double_tap_power_gesture_disabled",  "1" },
        { C_M_LOCK_MENU, "system", "lockscreen_enable_power_menu",              "0" },  // crDroid / Lineage
        { C_M_QS_MENU,   "system", "qs_footer_show_power_menu",                 "0" },  // crDroid
    };
}
