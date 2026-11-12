package io.github.albatrossmicrosystems.lazarus;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntConsumer;

/** Settings screen. Values are read from Settings.Global and changed via the hook (broadcast). */
public class MainActivity extends Activity {

    private LinearLayout root;
    private TextView status, exitInfo;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, String> sent = new HashMap<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setFitsSystemWindows(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, dp(32));
        sv.addView(root);
        setContentView(sv);
        build();
        if (!getSharedPreferences(GuideActivity.PREFS, MODE_PRIVATE).getBoolean(GuideActivity.SEEN, false)) {
            startActivity(new Intent(this, GuideActivity.class));        // first launch: show the tutorial
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    // ------------------------------------------------------------------ layout
    private void build() {
        root.addView(text("Play dead. Stay findable.", 22, true));
        root.addView(hint("Lazarus " + Keys.VERSION + " · by Niik"));
        root.addView(text("Anti-theft: when a thief switches your phone off, it only looks off, and it keeps telling you where it is.", 14, false));
        root.addView(button("How to use · setup checklist", v -> startActivity(new Intent(this, GuideActivity.class))));

        status = text("", 14, false);
        status.setPadding(0, dp(12), 0, dp(4));
        root.addView(status);

        LinearLayout power = row();
        power.addView(button("Power off", v -> startPower("shutdown")), weight());
        power.addView(button("Restart", v -> startPower("reboot")), weight());
        root.addView(power);
        root.addView(hint("Real power off / restart. Asks for your PIN (fingerprint is not accepted)."));

        header("General");
        toggle(Keys.C_ENABLED, "Lazarus enabled",
                "Off = stock power button behaviour; all changed system settings are restored.");

        header("Fake power-off");
        number(Keys.C_HOLD_OFF, "Hold power to fake power-off (ms)");
        toggle(Keys.C_SILENCE, "Total silence while off", "Do Not Disturb (no calls, alarms or sounds).");
        toggle(Keys.C_NO_VIBRATE, "No vibration while off", null);
        toggle(Keys.C_BLOCK_SHUTDOWN, "Turn power-off requests into fake power-off",
                "Power menu / apps cannot really shut down. Low-battery and overheating shutdowns are still allowed.");

        header("Fake reboot (while fake-off)");
        toggle(Keys.C_FAKE_REBOOT, "Holding power plays a fake reboot",
                "Boot animation, then the lock screen — the phone never really restarts, so trackers keep running.");
        number(Keys.C_HOLD_REBOOT, "Hold power for fake reboot (ms)");
        root.addView(hint("Keep this well below your device's hardware reset time (often 6–10 s of holding power). "
                + "Past that, the phone really restarts and no software can stop it."));
        number(Keys.C_BOOT_ANIM, "Boot animation length (ms)");
        Button anim = button("Install boot-animation support (root)", v -> installSepolicy());
        root.addView(anim);

        header("Owner exit (while fake-off)");
        toggle(Keys.C_OWNER_EXIT, "Owner exit: wake from fake power-off", null);
        root.addView(text("Button combination", 14, false));
        exitInfo = hint("");
        choice(Keys.C_EXIT_COMBO, Keys.EXIT_LABELS, i -> updateExitInfo());
        root.addView(exitInfo);
        number(Keys.C_HOLD_EXIT, "Hold time (ms)");
        updateExitInfo();

        header("Dark wake");
        textPref(Keys.C_DARK_PKGS, Keys.DEF_DARK_PKGS, "Apps allowed to use the screen invisibly while off",
                "Comma-separated packages. Needed e.g. for FMD's remote camera. The backlight stays off.");

        header("System settings Lazarus may change");
        toggle(Keys.C_M_SOS, "Disable Emergency SOS (5× power)", "Otherwise rapid presses reveal the phone is on.");
        toggle(Keys.C_M_CAMERA, "Disable double-press camera", null);
        toggle(Keys.C_M_LOCK_MENU, "Hide power menu on lock screen", "crDroid / LineageOS setting.");
        toggle(Keys.C_M_QS_MENU, "Hide Quick Settings power button", "crDroid setting.");
        root.addView(hint("Original values are restored when an option, Lazarus, or the app is removed."));

        header("ADB");
        root.addView(mono("adb shell settings put global lazarus_cmd exit\n"
                + "adb shell settings put global lazarus_cmd enter\n"
                + "adb shell settings put global lazarus_cmd shutdown\n"
                + "adb shell settings put global lazarus_cmd reboot"));
    }

    // ------------------------------------------------------------------ status
    private void refreshStatus() {
        String s = Settings.Global.getString(getContentResolver(), Keys.STATUS);
        String anim = Settings.Global.getString(getContentResolver(), Keys.BOOTANIM);
        boolean off = Settings.Global.getInt(getContentResolver(), Keys.STATE, 0) == 1;
        StringBuilder sb = new StringBuilder();
        boolean active = false;
        if (s != null && s.startsWith("active;")) {
            String[] p = s.split(";");
            try {
                long bootWall = System.currentTimeMillis() - SystemClock.elapsedRealtime();
                active = p.length >= 4 && Math.abs(Long.parseLong(p[3]) - bootWall) < 120000;
            } catch (NumberFormatException ignored) {}
            if (active) sb.append("● Active  (v").append(p[1]).append(", hooks ").append(p[2]).append(")");
        }
        if (!active) sb.append("○ Not active — enable Lazarus in Vector/LSPosed (scope: System Framework) and reboot.");
        if (active && off) sb.append("\nCurrently in fake power-off.");
        if ("failed".equals(anim)) sb.append("\nBoot animation blocked by SELinux — tap 'Install boot-animation support'.");
        status.setText(sb);
    }

    private int getInt(String key) {
        try { return Keys.clamp(key, Integer.parseInt(get(key, String.valueOf(Keys.def(key))).trim())); }
        catch (NumberFormatException e) { return Keys.def(key); }
    }

    /** Describes the selected owner exit gesture and its catch. */
    private void updateExitInfo() {
        int combo = getInt(Keys.C_EXIT_COMBO);
        String s = "To wake: " + Keys.exitGesture(combo, get(Keys.C_EXIT_SEQ, Keys.DEF_EXIT_SEQ), getInt(Keys.C_HOLD_EXIT)) + ".";
        switch (combo) {
            case Keys.EXIT_VOL_UP: case Keys.EXIT_VOL_DOWN:
                s += " A single key is easier for someone else to hold by accident."; break;
            case Keys.EXIT_PWR_VOL_UP:
                s += " Press Vol-Up first, then Power, and hold both. Holding power alone still plays the fake reboot."; break;
            case Keys.EXIT_SEQUENCE:
                s += " At most 2 s between presses; pressing power starts over."; break;
        }
        exitInfo.setText(s);
    }

    private void startPower(String cmd) {
        startActivity(new Intent(this, PowerActivity.class).putExtra(Keys.EXTRA_CMD, cmd));
    }

    private void set(String key, String value) {
        sent.put(key, value);
        sendBroadcast(new Intent(Keys.ACTION_SET).putExtra(Keys.EXTRA_KEY, key).putExtra(Keys.EXTRA_VALUE, value));
        ui.postDelayed(this::refreshStatus, 400);
    }

    private String get(String key, String def) {
        // the hook writes Settings.Global asynchronously: prefer what this screen just sent
        String v = sent.containsKey(key) ? sent.get(key) : Settings.Global.getString(getContentResolver(), Keys.CFG + key);
        return v == null ? def : v;
    }

    // ------------------------------------------------------------------ boot animation rule
    private void installSepolicy() {
        new AlertDialog.Builder(this)
                .setTitle("Boot-animation support")
                .setMessage("Installs a tiny Magisk module (lazarus_sepolicy) that lets the system start the "
                        + "boot animation for the fake reboot, and applies it immediately.\n\nRequires Magisk root.")
                .setPositiveButton("Install", (d, w) -> new Thread(this::runSepolicyInstall).start())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runSepolicyInstall() {
        String result;
        try {
            File zip = new File(getCacheDir(), "lazarus_sepolicy.zip");
            try (InputStream in = getAssets().open("lazarus_sepolicy.zip"); OutputStream out = new FileOutputStream(zip)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            String cmd = "magisk --install-module '" + zip.getAbsolutePath() + "' && "
                    + "magiskpolicy --live 'allow system_server ctl_start_prop property_service set' && echo LAZARUS_OK";
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line; while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            p.waitFor();
            result = out.toString().contains("LAZARUS_OK")
                    ? "Installed. The fake reboot will now show the boot animation."
                    : "Failed:\n" + out;
        } catch (Throwable t) {
            result = "Failed (is the app granted root?):\n" + t;
        }
        final String msg = result;
        ui.post(() -> new AlertDialog.Builder(this).setTitle("Boot-animation support").setMessage(msg)
                .setPositiveButton("OK", null).show());
    }

    // ------------------------------------------------------------------ widgets
    private void toggle(String key, String label, String hint) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        s.setPadding(0, dp(8), 0, dp(8));
        s.setChecked("1".equals(get(key, String.valueOf(Keys.def(key)))));
        s.setOnCheckedChangeListener((v, checked) -> set(key, checked ? "1" : "0"));
        root.addView(s);
        if (hint != null) root.addView(hint(hint));
    }

    private void number(String key, String label) {
        root.addView(text(label, 14, false));
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(get(key, String.valueOf(Keys.def(key))));
        Object[] spec = Keys.spec(key);
        e.setHint(spec[2] + "–" + spec[3]);
        e.setOnFocusChangeListener((v, has) -> { if (!has) commitNumber(key, e); });
        e.setOnEditorActionListener((v, a, ev) -> { commitNumber(key, e); return false; });
        root.addView(e);
    }

    private void commitNumber(String key, EditText e) {
        try {
            int v = Keys.clamp(key, Integer.parseInt(e.getText().toString().trim()));
            e.setText(String.valueOf(v));
            set(key, String.valueOf(v));
            if (Keys.C_HOLD_EXIT.equals(key)) updateExitInfo();
        } catch (NumberFormatException ex) {
            e.setText(get(key, String.valueOf(Keys.def(key))));
        }
    }

    /** Single choice stored as the option index. */
    private void choice(String key, String[] labels, IntConsumer onPick) {
        RadioGroup g = new RadioGroup(this);
        int cur = getInt(key);
        for (int i = 0; i < labels.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(labels[i]);
            rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            g.addView(rb);
            if (i == cur) rb.setChecked(true);
        }
        g.setOnCheckedChangeListener((group, id) -> {
            int i = group.indexOfChild(group.findViewById(id));
            if (i < 0) return;
            set(key, String.valueOf(i));
            if (onPick != null) onPick.accept(i);
        });
        root.addView(g);
    }

    private void textPref(String key, String def, String label, String hint) {
        root.addView(text(label, 14, false));
        EditText e = new EditText(this);
        e.setText(get(key, def));
        e.setOnFocusChangeListener((v, has) -> { if (!has) set(key, e.getText().toString().trim()); });
        e.setOnEditorActionListener((v, a, ev) -> { set(key, e.getText().toString().trim()); return false; });
        root.addView(e);
        if (hint != null) root.addView(hint(hint));
    }

    private void header(String s) {
        TextView t = text(s, 13, true);
        t.setAllCaps(true);
        t.setPadding(0, dp(24), 0, dp(4));
        root.addView(t);
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

    private TextView hint(String s) {
        TextView t = text(s, 12, false);
        t.setAlpha(0.7f);
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    private TextView mono(String s) {
        TextView t = text(s, 12, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        btn.setOnClickListener(l);
        return btn;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
