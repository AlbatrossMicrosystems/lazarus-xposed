package io.github.albatrossmicrosystems.lazarus;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Lazarus — fake power-off for rooted Android, running inside system_server (scope: "android").
 *
 *  NORMAL   : hold POWER (1.5s)                -> FAKE-OFF
 *  FAKE-OFF : keys / touch / every wake source  -> swallowed, the screen never turns on
 *             hold POWER (3s)                   -> FAKE reboot (boot animation, then lock screen)
 *             owner exit combo                  -> back to NORMAL
 *               (default: hold VOL-UP + VOL-DOWN 3s; see Keys.EXIT_* for the alternatives)
 *  Power-off requests (menus, apps)             -> FAKE-OFF instead (battery/thermal shutdowns allowed)
 *  Allow-listed apps (default: FMD) may wake the panel with the backlight forced OFF ("dark wake").
 *
 *  Control: the Lazarus app (signature-protected broadcast) or adb:
 *    adb shell settings put global lazarus_cmd enter|exit|reboot|shutdown
 *    adb shell settings put global lazarus_cfg_<option> <value>
 *
 * Safety: fake-off lives only in memory, so every real boot starts NORMAL; every hook is wrapped
 * so an exception falls through to stock Android; changed system settings are restored when the
 * module is switched off or uninstalled.
 */
public class Lazarus implements IXposedHookLoadPackage {
    static final String TAG = "Lazarus";
    static final int HALT_MODE_SHUTDOWN = 0;
    static final int ZEN_OFF = 0, ZEN_NONE = 2;
    static final float BRIGHTNESS_OFF = -1.0f;                 // PowerManager.BRIGHTNESS_OFF_FLOAT
    static final long DARK_WAKE_MS = 15000, BOOT_TAIL_MS = 1500, BOOT_FALLBACK_MS = 8000;

    static volatile boolean fake, allowWake, allowShutdown, ready, booting, removed;
    static volatile boolean silenced;                          // we switched DND on for this fake-off
    static volatile boolean animOk;

    static Object pwmObj;                                      // PhoneWindowManager instance
    static Context ctx;
    static ContentResolver cr;
    static Handler h;
    static PowerManager pm;
    static PowerManager.WakeLock wl, bootWl;
    static int savedZen = ZEN_OFF;
    static int hooksOk, hooksTotal;

    // key state (touched from the input thread)
    static boolean pwrDown, vuDown, vdDown, pwrDownPassed;

    static void log(String s) {
        Log.i(TAG, s);
        try { XposedBridge.log(TAG + ": " + s); } catch (Throwable ignored) {}
    }

    // ================================================================== config
    static int cfg(String name) {
        try { return Keys.clamp(name, Settings.Global.getInt(cr, Keys.CFG + name, Keys.def(name))); }
        catch (Throwable t) { return Keys.def(name); }
    }
    static boolean on(String name) { return cfg(name) == 1; }
    static boolean enabled() { return ready && !removed && on(Keys.C_ENABLED); }

    static boolean isDarkWakePkg(String pkg) {
        if (pkg == null) return false;
        String list = Settings.Global.getString(cr, Keys.CFG + Keys.C_DARK_PKGS);
        if (list == null) list = Keys.DEF_DARK_PKGS;
        for (String p : list.split("[,\\s]+")) if (p.trim().equals(pkg)) return true;
        return false;
    }

    // ================================================================== hooks
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!"android".equals(lp.packageName)) return;
        ClassLoader cl = lp.classLoader;
        log("loading v" + Keys.VERSION + " in system_server");

        Class<?> pwm  = find("com.android.server.policy.PhoneWindowManager", cl);
        Class<?> pms  = find("com.android.server.power.PowerManagerService", cl);
        Class<?> pg   = find("com.android.server.power.PowerGroup", cl);
        Class<?> sdt  = find("com.android.server.power.ShutdownThread", cl);
        Class<?> vms  = find("com.android.server.vibrator.VibratorManagerService", cl);
        Class<?> bla  = find("com.android.server.display.LocalDisplayAdapter$BacklightAdapter", cl);
        Class<?> atms = find("com.android.server.wm.ActivityTaskManagerService", cl);

        hook(pwm, "systemReady", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                pwmObj = p.thisObject;
                try { init((Context) XposedHelpers.getObjectField(p.thisObject, "mContext")); }
                catch (Throwable t) { log("init failed: " + Log.getStackTraceString(t)); }
            }
        });
        hook(pwm, "interceptKeyBeforeQueueing", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try { onKey(p); } catch (Throwable t) { log("onKey: " + t); }
            }
        });
        hook(pwm, "interceptMotionBeforeQueueingNonInteractive", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { if (fake) p.setResult(0); }
        });
        // The long-press action must exist (see Keys.MANAGED) but must do nothing: our own timer
        // decides what a long press means.
        XC_MethodHook noopWhenEnabled = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { if (enabled()) p.setResult(null); }
        };
        hook(pwm, "powerLongPress", noopWhenEnabled);
        hook(pwm, "powerVeryLongPress", noopWhenEnabled);
        hook(pwm, "showGlobalActionsInternal", noopWhenEnabled);

        XC_MethodHook blockWake = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { if (fake && !allowWake) p.setResult(null); }
        };
        hook(pms, "wakePowerGroupLocked", blockWake);
        hook(pg, "wakeUpLocked", blockWake);

        hook(pms, "shutdownOrRebootInternal", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    int mode = (Integer) p.args[0];
                    String reason = p.args.length > 2 ? (String) p.args[2] : null;
                    if (mode == HALT_MODE_SHUTDOWN && blockShutdown(reason)) p.setResult(null);
                } catch (Throwable t) { log("shutdown hook: " + t); }
            }
        });
        hook(sdt, "shutdown", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    String reason = p.args.length > 1 ? (String) p.args[1] : null;
                    if (blockShutdown(reason)) p.setResult(null);
                } catch (Throwable t) { log("ShutdownThread hook: " + t); }
            }
        });

        hook(vms, "vibrateInternal", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                // @Nullable return value: callers treat null as "vibration ignored"
                if (fake && !booting && on(Keys.C_NO_VIBRATE)) p.setResult(null);
            }
        });

        // Dark wake: while FAKE-OFF the panel backlight is forced OFF at the lowest level, so the
        // display may be awake (needed e.g. for FMD's camera activity) yet stays black.
        hook(bla, "setBacklight", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!fake || booting) return;
                for (int i = 0; i < p.args.length; i++) if (p.args[i] instanceof Float) p.args[i] = BRIGHTNESS_OFF;
            }
        });
        XC_MethodHook onStart = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    if (!fake || booting) return;
                    String caller = p.args.length > 1 && p.args[1] instanceof String ? (String) p.args[1] : null;
                    String target = null;
                    for (Object a : p.args) if (a instanceof Intent) {
                        ComponentName c = ((Intent) a).getComponent();
                        if (c != null) target = c.getPackageName();
                    }
                    if (isDarkWakePkg(caller) || isDarkWakePkg(target)) darkWake(caller != null ? caller : target);
                } catch (Throwable t) { log("startActivity hook: " + t); }
            }
        };
        hook(atms, "startActivity", onStart);
        hook(atms, "startActivityAsUser", onStart);
    }

    static Class<?> find(String name, ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists(name, cl);
        if (c == null) log("class not found: " + name);
        return c;
    }

    static void hook(Class<?> c, String m, XC_MethodHook cb) {
        hooksTotal++;
        if (c == null) return;
        try {
            int n = XposedBridge.hookAllMethods(c, m, cb).size();
            if (n > 0) hooksOk++;
            log("hooked " + c.getSimpleName() + "." + m + " x" + n);
        } catch (Throwable t) { log("FAILED hook " + c.getSimpleName() + "." + m + ": " + t); }
    }

    // ================================================================== setup
    static void init(Context c) {
        if (ready || c == null) return;
        ctx = c;
        cr = c.getContentResolver();
        HandlerThread ht = new HandlerThread("Lazarus");
        ht.start();
        h = new Handler(ht.getLooper());
        pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Lazarus:key");
        wl.setReferenceCounted(false);
        @SuppressWarnings("deprecation")
        PowerManager.WakeLock b = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, "Lazarus:boot");
        bootWl = b;
        bootWl.setReferenceCounted(false);
        ready = true;

        h.post(() -> {
            try {
                Settings.Global.putString(cr, Keys.CMD, "");
                // Still marked FAKE-OFF from the previous boot => a hardware reset (power key held
                // past the PMIC timer) happened during fake-off. Undo the DND we switched on.
                if (Settings.Global.getInt(cr, Keys.STATE, 0) == 1) {
                    int z = Settings.Global.getInt(cr, Keys.SAVED_ZEN, -1);
                    log("recovering from hard reset during fake-off, zen=" + z);
                    if (z >= 0) { setZen(z); h.postDelayed(() -> setZen(z), 30000); }
                }
                Settings.Global.putInt(cr, Keys.STATE, 0);
                applyManaged();

                cr.registerContentObserver(Settings.Global.CONTENT_URI, true, new ContentObserver(h) {
                    @Override public void onChange(boolean self, Uri uri) {
                        String k = uri == null ? "" : uri.getLastPathSegment();
                        if (Keys.CMD.equals(k)) onAdbCommand();
                        else if (k != null && k.startsWith(Keys.CFG)) onConfigChanged();
                    }
                });
                registerReceivers();
                long bootWall = System.currentTimeMillis() - SystemClock.elapsedRealtime();
                Settings.Global.putString(cr, Keys.STATUS,
                        "active;" + Keys.VERSION + ";" + hooksOk + "/" + hooksTotal + ";" + bootWall);
                log("ready (" + hooksOk + "/" + hooksTotal + " hooks, enabled=" + on(Keys.C_ENABLED) + ")");
            } catch (Throwable t) { log("init: " + Log.getStackTraceString(t)); }
        });
    }

    static void registerReceivers() {
        BroadcastReceiver control = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                try {
                    if (Keys.ACTION_CMD.equals(i.getAction())) {
                        command(i.getStringExtra(Keys.EXTRA_CMD), "app");
                    } else if (Keys.ACTION_SET.equals(i.getAction())) {
                        String k = i.getStringExtra(Keys.EXTRA_KEY), v = i.getStringExtra(Keys.EXTRA_VALUE);
                        if (k != null && Keys.isKnown(k)) {
                            Settings.Global.putString(cr, Keys.CFG + k, v);   // observer applies it
                            log("config " + k + "=" + v);
                        }
                    }
                } catch (Throwable t) { log("control: " + t); }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Keys.ACTION_CMD);
        f.addAction(Keys.ACTION_SET);
        // only senders holding the signature permission (= the Lazarus app) get through
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(control, f, Keys.PERM, h, Context.RECEIVER_EXPORTED);
        else ctx.registerReceiver(control, f, Keys.PERM, h);

        BroadcastReceiver uninstall = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                try {
                    Uri d = i.getData();
                    if (d == null || !Keys.PKG.equals(d.getSchemeSpecificPart())) return;
                    if (i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
                    log("app uninstalled: restoring everything");
                    if (fake) exitFake("uninstall");
                    removed = true;
                    restoreAllManaged();
                    Settings.Global.putString(cr, Keys.STATUS, null);
                } catch (Throwable t) { log("uninstall: " + t); }
            }
        };
        IntentFilter pf = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
        pf.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(uninstall, pf, null, h, Context.RECEIVER_EXPORTED);
        else ctx.registerReceiver(uninstall, pf, null, h);
    }

    static void onConfigChanged() {
        try {
            if (!enabled() && fake) exitFake("disabled");
            applyManaged();
        } catch (Throwable t) { log("config: " + t); }
    }

    static void onAdbCommand() {
        try {
            String cmd = Settings.Global.getString(cr, Keys.CMD);
            if (cmd == null || cmd.isEmpty()) return;
            Settings.Global.putString(cr, Keys.CMD, "");
            command(cmd, "adb");
        } catch (Throwable t) { log("adb command: " + t); }
    }

    static void command(String cmd, String src) {
        if (cmd == null) return;
        log("command '" + cmd + "' from " + src);
        switch (cmd) {
            case "enter":    if (enabled()) enterFake(src); break;
            case "exit":     exitFake(src); break;
            case "reboot":   realReboot(); break;
            case "shutdown": realShutdown(); break;
        }
    }

    // ================================================================== managed system settings
    static void applyManaged() {
        boolean en = enabled();
        for (String[] m : Keys.MANAGED) {
            boolean want = en && (m[0] == null || on(m[0]));
            if (want) applySetting(m[1], m[2], m[3]); else restoreSetting(m[1], m[2]);
        }
    }

    static void restoreAllManaged() {
        for (String[] m : Keys.MANAGED) restoreSetting(m[1], m[2]);
    }

    static void applySetting(String table, String key, String value) {
        String origKey = Keys.ORIG + table + "_" + key;
        String cur = getSetting(table, key);
        if (Settings.Global.getString(cr, origKey) == null) {
            if (value.equals(cur)) return;                       // already as needed: nothing to undo later
            Settings.Global.putString(cr, origKey, cur == null ? Keys.ORIG_NONE : cur);
        }
        if (!value.equals(cur)) { putSetting(table, key, value); log("set " + table + "/" + key + "=" + value); }
    }

    static void restoreSetting(String table, String key) {
        String origKey = Keys.ORIG + table + "_" + key;
        String orig = Settings.Global.getString(cr, origKey);
        if (orig == null) return;
        putSetting(table, key, Keys.ORIG_NONE.equals(orig) ? null : orig);
        Settings.Global.putString(cr, origKey, null);
        log("restored " + table + "/" + key + "=" + orig);
    }

    static String getSetting(String table, String key) {
        switch (table) {
            case "global": return Settings.Global.getString(cr, key);
            case "secure": return Settings.Secure.getString(cr, key);
            default:       return Settings.System.getString(cr, key);
        }
    }

    static void putSetting(String table, String key, String value) {
        try {
            switch (table) {
                case "global": Settings.Global.putString(cr, key, value); break;
                case "secure": Settings.Secure.putString(cr, key, value); break;
                default:       Settings.System.putString(cr, key, value); break;
            }
        } catch (Throwable t) { log("put " + table + "/" + key + ": " + t); }
    }

    // ================================================================== keys
    static final Runnable offRun    = () -> enterFake("power hold");
    static final Runnable rebootRun = Lazarus::fakeReboot;
    static final Runnable exitRun   = () -> { exitArmed = false; exitFake("owner combo"); };
    static volatile boolean exitArmed;                         // owner exit hold timer running

    /** The owner exit combo in effect (Keys.EXIT_*). */
    static int exitCombo() {
        return cfg(Keys.C_EXIT_COMBO);
    }

    /** Is the chosen hold combo held right now (and nothing that would make it ambiguous)? */
    static boolean exitComboHeld(int combo) {
        switch (combo) {
            case Keys.EXIT_VOL_UP:   return vuDown && !vdDown && !pwrDown;
            case Keys.EXIT_VOL_DOWN: return vdDown && !vuDown && !pwrDown;
            default:                 return vuDown && vdDown;
        }
    }

    /** Arms / disarms the owner exit hold timer after a key state change in FAKE-OFF. */
    static void updateExit() {
        boolean want = !booting && on(Keys.C_OWNER_EXIT) && exitComboHeld(exitCombo());
        if (want && !exitArmed) {
            exitArmed = true;
            wl.acquire(cfg(Keys.C_HOLD_EXIT) + 3000);
            h.removeCallbacks(exitRun); h.postDelayed(exitRun, cfg(Keys.C_HOLD_EXIT));
        } else if (!want && exitArmed) {
            exitArmed = false;
            h.removeCallbacks(exitRun);
        }
    }

    static void onKey(XC_MethodHook.MethodHookParam p) {
        if (!ready) return;
        if (!enabled() && !fake) return;
        KeyEvent ev = (KeyEvent) p.args[0];
        int code = ev.getKeyCode();
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = down && ev.getRepeatCount() == 0;
        boolean pwr = code == KeyEvent.KEYCODE_POWER;
        boolean vu  = code == KeyEvent.KEYCODE_VOLUME_UP;
        boolean vd  = code == KeyEvent.KEYCODE_VOLUME_DOWN;

        synchronized (Lazarus.class) {
            if (pwr || vu || vd) {
                // key state: only the first DOWN and the UP change it, auto-repeats do not
                if (first || !down) {
                    if (pwr) pwrDown = down; else if (vu) vuDown = down; else vdDown = down;
                }
                if (first && (pwr || fake)) wl.acquire(10000);  // timers must survive deep sleep

                if (pwr && (first || !down)) { h.removeCallbacks(offRun); h.removeCallbacks(rebootRun); }
                if ((vu || vd) && down) h.removeCallbacks(offRun); // power+volume combos are not ours in NORMAL

                if (fake) {
                    updateExit();
                    if (pwr && first && !booting && !exitArmed && on(Keys.C_FAKE_REBOOT))
                        h.postDelayed(rebootRun, cfg(Keys.C_HOLD_REBOOT));
                } else if (pwr && first && !vuDown && !vdDown) {
                    h.postDelayed(offRun, cfg(Keys.C_HOLD_OFF));
                }
                if (!pwrDown && !vuDown && !vdDown) releaseWl();
            }

            if (fake) {
                // Let the UP of a power press whose DOWN Android already saw (the press that
                // triggered fake-off) through, so Android's key state is not left "held".
                if (code == KeyEvent.KEYCODE_POWER && !down && pwrDownPassed) { pwrDownPassed = false; return; }
                p.setResult(0);                                  // swallow: no wake, no SOS, no UI
                return;
            }
            if (code == KeyEvent.KEYCODE_POWER) pwrDownPassed = down;
        }
    }

    static void releaseWl() { try { if (wl.isHeld()) wl.release(); } catch (Throwable ignored) {} }

    // ================================================================== fake-off
    static void enterFake(String why) {
        if (fake || !enabled()) return;
        vibrate(250);
        savedZen = Settings.Global.getInt(cr, "zen_mode", ZEN_OFF);
        silenced = on(Keys.C_SILENCE);
        if (silenced) setZen(ZEN_NONE);
        fake = true;
        putState(1);
        try { XposedHelpers.callMethod(pm, "goToSleep", SystemClock.uptimeMillis(), 4 /*POWER_BUTTON*/, 1 /*NO_DOZE*/); }
        catch (Throwable t) { log("goToSleep: " + t); }
        log("FAKE-OFF (" + why + ")");
    }

    static void exitFake(String why) {
        if (!fake) return;
        cancelBoot();
        h.removeCallbacks(darkEnd);
        h.removeCallbacks(exitRun); exitArmed = false;
        allowWake = false;
        boolean wasAwake = pm.isInteractive();                  // dark wake in progress
        fake = false;
        putState(0);
        restoreZen();
        try {
            if (wasAwake) {
                // the backlight was forced off while awake; cycle the display so the real
                // brightness reaches the panel again
                XposedHelpers.callMethod(pm, "goToSleep", SystemClock.uptimeMillis(), 4, 1);
                SystemClock.sleep(600);
            }
            XposedHelpers.callMethod(pm, "wakeUp", SystemClock.uptimeMillis(), 1 /*POWER_BUTTON*/, "lazarus:" + why);
        } catch (Throwable t) { log("wakeUp: " + t); }
        log("EXIT FAKE-OFF (" + why + ")");
    }

    // ================================================================== dark wake
    static final Runnable darkEnd = () -> {
        allowWake = false;
        if (fake && !booting) {
            try { XposedHelpers.callMethod(pm, "goToSleep", SystemClock.uptimeMillis(), 4, 1); }
            catch (Throwable t) { log("darkEnd: " + t); }
            log("dark wake ended");
        }
    };

    /** Runs on the caller's binder thread, before its activity is launched. */
    static void darkWake(String pkg) {
        if (!ready) return;
        long id = Binder.clearCallingIdentity();
        try {
            log("dark wake for " + pkg);
            h.removeCallbacks(darkEnd);
            allowWake = true;                                    // let turnScreenOn etc. through
            if (!pm.isInteractive()) {
                XposedHelpers.callMethod(pm, "wakeUp", SystemClock.uptimeMillis(), 2 /*APPLICATION*/, "lazarus:darkwake");
                SystemClock.sleep(400);                          // let WM drop its sleep token first
            }
            h.postDelayed(darkEnd, DARK_WAKE_MS);
        } catch (Throwable t) {
            log("darkWake: " + t);
            allowWake = false;
        } finally {
            Binder.restoreCallingIdentity(id);
        }
    }

    // ================================================================== fake reboot
    // A real reboot would leave the phone "before first unlock" (credential-encrypted storage
    // locked), where normal apps such as trackers cannot run. Instead we replay what a cold boot
    // looks like and never actually restart.
    static void fakeReboot() {
        if (!fake || booting) return;
        booting = true;
        log("FAKE REBOOT: start");
        h.post(bootStart);
    }

    static final Runnable bootStart = () -> {
        vibrate(120);                                           // the short buzz of a real power-on
        animOk = setProp("service.bootanim.exit", "0") && setProp("ctl.start", "bootanim");
        Settings.Global.putString(cr, Keys.BOOTANIM, animOk ? "ok" : "failed");
        if (animOk) {
            // give bootanimation time to put its surface on top before the panel lights up
            h.postDelayed(() -> {
                allowWake = true;
                try { XposedHelpers.callMethod(pm, "wakeUp", SystemClock.uptimeMillis(), 2, "lazarus:boot"); }
                catch (Throwable t) { log("boot wake: " + t); }
                // a real boot never times the screen out mid-animation
                try { bootWl.acquire(cfg(Keys.C_BOOT_ANIM) + BOOT_TAIL_MS + 5000); } catch (Throwable t) { log("bootWl: " + t); }
                h.postDelayed(Lazarus.bootStopAnim, cfg(Keys.C_BOOT_ANIM));
            }, 350);
        } else {
            // No permission to start the boot animation (lazarus_sepolicy not installed): stay
            // dark for a while, then light up straight into the lock screen.
            log("boot animation unavailable, using dark fallback");
            h.postDelayed(Lazarus.bootFinish, BOOT_FALLBACK_MS);
        }
    };

    static final Runnable bootStopAnim = () -> {
        setProp("service.bootanim.exit", "1");
        h.postDelayed(Lazarus.bootFinish, BOOT_TAIL_MS);
    };

    static final Runnable bootFinish = () -> {
        try { XposedHelpers.callMethod(pwmObj, "lockNow", (Object) null); } catch (Throwable t) { log("lockNow: " + t); }
        booting = false;
        allowWake = false;
        fake = false;
        putState(0);
        restoreZen();
        try {
            if (!pm.isInteractive()) XposedHelpers.callMethod(pm, "wakeUp", SystemClock.uptimeMillis(), 2, "lazarus:boot");
            XposedHelpers.callMethod(pm, "userActivity", SystemClock.uptimeMillis(), false);
        } catch (Throwable t) { log("bootFinish wake: " + t); }
        releaseBootWl();                                        // ON_AFTER_RELEASE: lock screen stays up a while
        log("FAKE REBOOT: done");
    };

    static void cancelBoot() {
        if (!booting) return;
        h.removeCallbacks(bootStart); h.removeCallbacks(bootStopAnim); h.removeCallbacks(bootFinish);
        if (animOk) setProp("service.bootanim.exit", "1");
        releaseBootWl();
        booting = false;
    }

    static void releaseBootWl() { try { if (bootWl.isHeld()) bootWl.release(); } catch (Throwable ignored) {} }

    static boolean setProp(String k, String v) {
        try {
            Class.forName("android.os.SystemProperties").getMethod("set", String.class, String.class).invoke(null, k, v);
            return true;
        } catch (Throwable t) { log("setprop " + k + " failed: " + t); return false; }
    }

    // ================================================================== real power (owner)
    static void realReboot() {
        log("REAL REBOOT");
        if (fake) { restoreZen(); fake = false; putState(0); }
        try { pm.reboot(null); } catch (Throwable t) { log("reboot: " + t); }
    }

    static void realShutdown() {
        log("REAL SHUTDOWN");
        if (fake) { restoreZen(); fake = false; putState(0); }
        allowShutdown = true;
        try { XposedHelpers.callMethod(pm, "shutdown", false, "lazarus-owner", false); }
        catch (Throwable t) { log("shutdown: " + t); allowShutdown = false; }
    }

    /** true = block this power-off and fake it instead */
    static boolean blockShutdown(String reason) {
        if (allowShutdown || !enabled() || !on(Keys.C_BLOCK_SHUTDOWN)) return false;
        String r = reason == null ? "" : reason.toLowerCase();
        if (r.contains("battery") || r.contains("thermal")) { log("allowing shutdown: " + reason); return false; }
        log("blocked shutdown (" + reason + ") -> fake-off");
        if (h != null) h.post(() -> enterFake("shutdown request"));
        return true;
    }

    // ================================================================== helpers
    static void vibrate(long ms) {
        try {
            Vibrator v = ctx.getSystemService(Vibrator.class);
            if (v != null) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Throwable t) { log("vibrate: " + t); }
    }

    static void restoreZen() {
        if (silenced) { setZen(savedZen); silenced = false; }
    }

    static void setZen(int mode) {
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            try { XposedHelpers.callMethod(nm, "setZenMode", mode, null, "lazarus"); }
            catch (Throwable t1) { XposedHelpers.callMethod(nm, "setZenMode", mode, null, "lazarus", false); }
        } catch (Throwable t) { log("setZen(" + mode + "): " + t); }
    }

    static void putState(int s) {
        try {
            // survives a hardware reset, so the next boot can undo our DND
            if (s == 1) Settings.Global.putInt(cr, Keys.SAVED_ZEN, silenced ? savedZen : -1);
            Settings.Global.putInt(cr, Keys.STATE, s);
        } catch (Throwable ignored) {}
    }
}
