package io.github.albatrossmicrosystems.lazarus;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.widget.Toast;

/**
 * Owner-only REAL power off / restart.
 * Requires the device PIN / pattern / password (DEVICE_CREDENTIAL only — biometrics are NOT
 * accepted, so nobody can force your finger onto the sensor). On success the command is sent to
 * the hook in system_server over a signature-protected broadcast.
 *
 * Optional extra "cmd" = "shutdown" | "reboot" skips the choice dialog.
 */
public class PowerActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String preset = getIntent().getStringExtra(Keys.EXTRA_CMD);
        if ("shutdown".equals(preset) || "reboot".equals(preset)) { confirm(preset); return; }

        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Real power")
                .setMessage("This REALLY turns the phone off or restarts it.\n\n"
                        + "Tracking apps stop working until the phone is switched on and unlocked with your PIN.")
                .setPositiveButton("Power off", (x, w) -> confirm("shutdown"))
                .setNeutralButton("Restart", (x, w) -> confirm("reboot"))
                .setNegativeButton("Cancel", (x, w) -> finish())
                .setOnCancelListener(x -> finish())
                .create();
        d.setCanceledOnTouchOutside(true);
        d.show();
    }

    private void confirm(String cmd) {
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km == null || !km.isDeviceSecure()) { send(cmd); return; }

        BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                .setTitle("shutdown".equals(cmd) ? "Confirm power off" : "Confirm restart")
                .setSubtitle("Enter your PIN, pattern or password")
                .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();
        prompt.authenticate(new CancellationSignal(), getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) { send(cmd); }
                    @Override public void onAuthenticationError(int code, CharSequence msg) { finish(); }
                });
    }

    private void send(String cmd) {
        sendBroadcast(new Intent(Keys.ACTION_CMD).putExtra(Keys.EXTRA_CMD, cmd));
        Toast.makeText(this, "shutdown".equals(cmd) ? "Powering off…" : "Restarting…", Toast.LENGTH_SHORT).show();
        finish();
    }
}
