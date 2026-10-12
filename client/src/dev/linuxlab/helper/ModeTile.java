package dev.linuxlab.helper;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick settings tile: lit while the screen works as a touchpad, dark while it is a touchscreen. */
public class ModeTile extends TileService {
    @Override
    public void onStartListening() {
        show();
    }

    @Override
    public void onClick() {
        Prefs.setCursorMode(this, !Prefs.cursorMode(this));
        show();
    }

    private void show() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        boolean cursor = Prefs.cursorMode(this);
        tile.setState(cursor ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.tile_label));
        tile.setSubtitle(getString(cursor ? R.string.mode_cursor : R.string.mode_touch));
        tile.updateTile();
    }
}
