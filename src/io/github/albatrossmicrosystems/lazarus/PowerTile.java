package io.github.albatrossmicrosystems.lazarus;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile that opens the PIN-protected real power menu. */
public class PowerTile extends TileService {

    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t != null) { t.setState(Tile.STATE_INACTIVE); t.updateTile(); }
    }

    @Override
    public void onClick() {
        if (isLocked()) unlockAndRun(this::launch); else launch();
    }

    @SuppressWarnings("deprecation")
    private void launch() {
        Intent i = new Intent(this, PowerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(i);
        }
    }
}
