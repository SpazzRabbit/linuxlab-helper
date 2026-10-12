package dev.linuxlab.helper;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Installing into Linux. Nothing from Android can run a command inside Linux Lab, so the person
 * does it once: this screen asks what to install, puts the installer into Downloads, copies the
 * command and says, step by step, where to paste it.
 */
public class InstallActivity extends Activity {
    static final String EXTRA_WHAT = "what";
    static final String UBUNTU = "ubuntu";
    static final String TOUCH = "touch";
    static final String REMOVE = "remove";
    static final String UPDATE = "update";

    private static final String ASSET = "linuxlab-helper.sh";
    /** Linux Lab shows the shared storage of the tablet as /tablet */
    private static final String LINUX_DOWNLOADS = "/tablet/Download/";

    private static int bundledSerial = -1;

    private TextView title;
    private TextView subtitle;
    private View pageChoose;
    private View pageRun;
    private View optionUbuntu;
    private View optionTouch;
    private ViewGroup runSteps;
    private View commandBox;
    private TextView command;
    private String choice = UBUNTU;
    private OnBackInvokedCallback backToChoice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.install);
        Ui.column(findViewById(R.id.scroll), findViewById(R.id.content));
        title = findViewById(R.id.title);
        subtitle = findViewById(R.id.subtitle);
        pageChoose = findViewById(R.id.page_choose);
        pageRun = findViewById(R.id.page_run);
        optionUbuntu = findViewById(R.id.option_ubuntu);
        optionTouch = findViewById(R.id.option_touch);
        runSteps = findViewById(R.id.run_steps);
        commandBox = findViewById(R.id.command_box);
        command = findViewById(R.id.command);

        findViewById(R.id.back).setOnClickListener(v -> back());
        optionUbuntu.setOnClickListener(v -> choose(UBUNTU));
        optionTouch.setOnClickListener(v -> choose(TOUCH));
        findViewById(R.id.next).setOnClickListener(v -> run(choice));
        findViewById(R.id.copy).setOnClickListener(v -> {
            copy();
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.open).setOnClickListener(v -> Linux.open(this));
        findViewById(R.id.done).setOnClickListener(v -> finish());
        backToChoice = this::showChoice;

        String what = getIntent().getStringExtra(EXTRA_WHAT);
        if (REMOVE.equals(what) || UPDATE.equals(what)) {
            run(what);
        } else {
            choose(TOUCH.equals(what) ? TOUCH : UBUNTU);
            showChoice();
        }
    }

    private void choose(String what) {
        choice = what;
        optionUbuntu.setSelected(UBUNTU.equals(what));
        optionTouch.setSelected(TOUCH.equals(what));
    }

    private void showChoice() {
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backToChoice);
        title.setText(R.string.install_title);
        subtitle.setText(R.string.install_subtitle);
        pageChoose.setVisibility(View.VISIBLE);
        pageRun.setVisibility(View.GONE);
    }

    private void back() {
        if (pageRun.getVisibility() == View.VISIBLE && !REMOVE.equals(choice) && !UPDATE.equals(choice)) {
            showChoice();
        } else {
            finish();
        }
    }

    /** Saves the installer, copies the command for @what and shows where to run it. */
    private void run(String what) {
        choice = what;
        String name;
        try {
            name = saveInstaller();
        } catch (IOException | RuntimeException e) {
            Toast.makeText(this, getString(R.string.save_failed, String.valueOf(e.getMessage())), Toast.LENGTH_LONG).show();
            return;
        }
        String path = LINUX_DOWNLOADS + name;
        String line = "bash " + (path.matches("[A-Za-z0-9/._-]+") ? path : "'" + path + "'");
        if (UBUNTU.equals(what) || UPDATE.equals(what) || REMOVE.equals(what)) {
            line += " " + what;
        }
        command.setText(line);
        copy();

        runSteps.removeAllViews();
        int n = 1;
        new Ui.Step(runSteps, n++, R.string.run_terminal, R.string.run_terminal_text);
        Ui.Step paste = new Ui.Step(runSteps, n++, R.string.run_paste, R.string.run_paste_text);
        ((ViewGroup) commandBox.getParent()).removeView(commandBox);
        paste.extra.addView(commandBox);
        commandBox.setVisibility(View.VISIBLE);
        if (!REMOVE.equals(what)) {
            new Ui.Step(runSteps, n++, UPDATE.equals(what) ? R.string.run_wait_update_title : R.string.run_wait,
                UBUNTU.equals(what) ? R.string.run_wait_ubuntu
                : UPDATE.equals(what) ? R.string.run_wait_update : R.string.run_wait_touch);
        }
        new Ui.Step(runSteps, n, R.string.run_restart, UBUNTU.equals(what) ? R.string.run_restart_ubuntu
            : UPDATE.equals(what) ? R.string.run_restart_update
            : REMOVE.equals(what) ? R.string.run_restart_remove : R.string.run_restart_touch);

        boolean alone = REMOVE.equals(what) || UPDATE.equals(what);
        title.setText(REMOVE.equals(what) ? R.string.remove_title : UPDATE.equals(what) ? R.string.update_title : R.string.run_title);
        subtitle.setText(alone ? R.string.run_title : R.string.run_subtitle);
        pageChoose.setVisibility(View.GONE);
        pageRun.setVisibility(View.VISIBLE);
        if (!alone) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backToChoice);
        }
    }

    private void copy() {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("command", command.getText()));
    }

    /**
     * The release serial of the Linux side this app brings (the line "serial=" that make_installer.sh
     * writes into the installer), 0 when unknown. Linux reports the serial it was set up with, see
     * Linux.desktop(); one set up from a newer release on GitHub is not offered this older one.
     */
    static synchronized int bundledSerial(Context context) {
        if (bundledSerial < 0) {
            bundledSerial = 0;
            try (BufferedReader in = new BufferedReader(new InputStreamReader(context.getAssets().open(ASSET), StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null && !line.equals("__PAYLOAD__"); ) {
                    if (line.startsWith("serial=")) {
                        bundledSerial = Integer.parseInt(line.substring("serial=".length()).trim());
                        break;
                    }
                }
            } catch (IOException | NumberFormatException e) {
                // no installer in this build
            }
        }
        return bundledSerial;
    }

    /** Copies the installer from the APK into the shared Downloads folder, where Linux Lab can read it. */
    private String saveInstaller() throws IOException {
        ContentResolver resolver = getContentResolver();
        Uri downloads = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        // a copy this app made earlier; files of other apps are neither seen nor touched
        resolver.delete(downloads, MediaStore.Downloads.DISPLAY_NAME + "=?", new String[] {ASSET});
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, ASSET);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/x-sh");
        Uri file = resolver.insert(downloads, values);
        if (file == null) {
            throw new IOException("MediaStore refused the file");
        }
        try (InputStream in = getAssets().open(ASSET); OutputStream out = resolver.openOutputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
            }
        }
        // the name differs from the one asked for when a file of another app is in the way
        try (Cursor row = resolver.query(file, new String[] {MediaStore.Downloads.DISPLAY_NAME}, null, null, null)) {
            if (row != null && row.moveToFirst()) {
                return row.getString(0);
            }
        }
        return ASSET;
    }
}
