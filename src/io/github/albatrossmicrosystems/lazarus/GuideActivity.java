package io.github.albatrossmicrosystems.lazarus;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** First-run tutorial and setup checklist. Also reachable from "How to use" in the settings. */
public class GuideActivity extends Activity {

    static final String PREFS = "lazarus_ui";
    static final String SEEN = "guide_seen_v1";

    private LinearLayout root;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(SEEN, true).apply();
        ScrollView sv = new ScrollView(this);
        sv.setFitsSystemWindows(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, dp(40));
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        root.removeAllViews();
        build();
    }

    private void build() {
        section("Play dead. Stay findable.");
        para("Lazarus makes your phone only pretend to switch off. When someone holds the power button, "
                + "the screen, sound and vibration go dead, but Android keeps running, so your tracking app "
                + "can still report where the phone is.\n\n"
                + "Holding power again plays a fake restart (boot animation, then the lock screen). The phone "
                + "never really restarts, because after a real restart tracking apps cannot run until someone "
                + "enters your PIN.");

        // ---------------------------------------------------------------- checklist
        section("1 · Setup checklist");
        boolean active = isActive();
        check(active, "Module active",
                active ? "Lazarus is running inside Android."
                       : "Open Vector (or LSPosed) → Modules → enable Lazarus → make sure \"System Framework\" "
                         + "is ticked in its scope → reboot the phone.");

        String anim = Settings.Global.getString(getContentResolver(), Keys.BOOTANIM);
        boolean animOk = "ok".equals(anim);
        check(animOk, "Boot-animation support",
                animOk ? "The fake restart shows the real boot animation."
                       : ("failed".equals(anim) ? "Blocked last time. " : "Not tested yet. ")
                         + "Settings → \"Install boot-animation support (root)\". Without it the fake restart "
                         + "stays dark for a few seconds, then lights up into the lock screen.");

        KeyguardManager km = getSystemService(KeyguardManager.class);
        boolean secure = km != null && km.isDeviceSecure();
        check(secure, "Screen lock (PIN / pattern / password)",
                secure ? "Set. Real power-off will ask for it."
                       : "Set a screen lock in Android Settings → Security. Without it anyone can use the phone "
                         + "and the real power-off button is unprotected.");

        boolean fmd = installed("de.nulide.findmydevice");
        check(fmd, "Tracking app",
                fmd ? "FMD (FindMyDevice) is installed. Give it location, SMS and battery-unrestricted "
                      + "permissions and connect it to an FMD server."
                    : "Install a tracker that works without Google, e.g. FMD from F-Droid "
                      + "(de.nulide.findmydevice). Google Find Hub works too while the phone is on.");

        para("Optional: add the \"Real power off\" tile to Quick Settings so you can really switch off "
                + "quickly (asks for your PIN).");
        if (Build.VERSION.SDK_INT >= 33) {
            root.addView(button("Add Quick Settings tile", v -> addTile()));
        } else {
            para("Pull down Quick Settings → edit (pencil) → drag \"Real power off\" into your tiles.");
        }

        // ---------------------------------------------------------------- gestures
        section("2 · Everyday use");
        row("Hold power ~1.5 s", "Fake power-off: one buzz, then the phone looks dead.");
        row("While \"off\": press power, tap the screen, call it", "Nothing happens. It stays dark and silent.");
        row("While \"off\": hold power ~3 s", "Fake restart: buzz → boot animation → lock screen.");
        row("While \"off\": hold Vol-Up + Vol-Down ~3 s", "Owner exit: wakes up normally.");
        row("Power menu / apps try to shut down", "Becomes a fake power-off instead.");
        row("Lazarus app → Power off / Restart", "REAL power off / restart. Asks for your PIN; fingerprint is not accepted.");

        // ---------------------------------------------------------------- try it
        section("3 · Try it now (2 minutes)");
        para("1. Hold power about 1.5 seconds. You feel one buzz and the screen goes black.\n"
                + "2. Press power a few times, tap the screen. Nothing should happen.\n"
                + "3. Hold Vol-Up + Vol-Down together for 3 seconds. The phone wakes up.\n"
                + "4. Hold power 1.5 s again, then hold power about 3 seconds. The boot animation appears; "
                + "let go. After ~17 s you see the lock screen and the phone is back to normal.\n"
                + "5. Open your tracking app's website and locate the phone while it is \"off\".");

        // ---------------------------------------------------------------- limits
        section("4 · Know the limits");
        para("• Hardware reset: every phone's power chip force-restarts the phone if the power key is held "
                + "long enough (often 6–10 s; power + volume-down can be sooner). No software can stop this. "
                + "Keep the fake-restart hold time well below it. After a real restart, tracking only works "
                + "again once your PIN is entered.\n"
                + "• You can no longer switch off with the power button. Use Lazarus → Power off.\n"
                + "• Not theft-proof: someone who knows Android can still wipe the phone through recovery "
                + "or fastboot. Lazarus defeats the everyday \"hold power to turn it off\".\n"
                + "• Battery: while \"off\" the phone is really just asleep, so it drains like a phone with "
                + "the screen off.");

        // ---------------------------------------------------------------- recovery
        section("5 · If something goes wrong");
        para("• Stuck dark: hold Vol-Up + Vol-Down 3 s, or from a computer run\n"
                + "  adb shell settings put global lazarus_cmd exit\n"
                + "• Turn Lazarus off in its settings (master switch). All changed system settings are restored.\n"
                + "• Disable the module in Vector and reboot.\n"
                + "• Emergency: boot into safe mode (hold Vol-Down while the phone starts). Magisk then disables "
                + "all modules.");

        root.addView(button("Open settings", v -> { startActivity(new Intent(this, MainActivity.class)); finish(); }));
    }

    // ------------------------------------------------------------------ checks
    private boolean isActive() {
        String s = Settings.Global.getString(getContentResolver(), Keys.STATUS);
        if (s == null || !s.startsWith("active;")) return false;
        String[] p = s.split(";");
        try {
            long bootWall = System.currentTimeMillis() - SystemClock.elapsedRealtime();
            return p.length >= 4 && Math.abs(Long.parseLong(p[3]) - bootWall) < 120000;
        } catch (NumberFormatException e) { return false; }
    }

    private boolean installed(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private void addTile() {
        if (Build.VERSION.SDK_INT < 33) return;
        StatusBarManager sbm = getSystemService(StatusBarManager.class);
        sbm.requestAddTileService(new ComponentName(this, PowerTile.class), "Real power off",
                Icon.createWithResource(this, android.R.drawable.ic_lock_power_off), getMainExecutor(),
                result -> Toast.makeText(this,
                        result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ? "Tile added"
                        : result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ? "Tile already added"
                        : "Tile not added", Toast.LENGTH_SHORT).show());
    }

    // ------------------------------------------------------------------ widgets
    private void title(String s) { root.addView(text(s, 24, true)); }

    private void section(String s) {
        TextView t = text(s, 17, true);
        t.setPadding(0, dp(24), 0, dp(6));
        root.addView(t);
    }

    private void para(String s) {
        TextView t = text(s, 15, false);
        t.setPadding(0, dp(4), 0, dp(8));
        t.setLineSpacing(0, 1.15f);
        root.addView(t);
    }

    private void check(boolean ok, String label, String detail) {
        TextView t = text((ok ? "✅  " : "⚠️  ") + label, 16, true);
        t.setPadding(0, dp(8), 0, 0);
        root.addView(t);
        TextView d = text(detail, 14, false);
        d.setAlpha(0.8f);
        d.setPadding(dp(30), 0, 0, dp(4));
        root.addView(d);
    }

    private void row(String what, String result) {
        TextView t = text(what, 15, true);
        t.setPadding(0, dp(6), 0, 0);
        root.addView(t);
        TextView r = text(result, 14, false);
        r.setAlpha(0.8f);
        r.setPadding(dp(12), 0, 0, dp(2));
        root.addView(r);
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.textColorPrimary, tv, true)) {
            t.setTextColor(tv.resourceId != 0 ? getColorStateList(tv.resourceId) : android.content.res.ColorStateList.valueOf(tv.data));
        }
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
