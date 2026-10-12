package dev.linuxlab.helper;

import android.app.Activity;
import android.app.StatusBarManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The only screen people see. One line says whether touches reach Linux. Until the setup is
 * done it shows the steps that are left, numbered, each with one button; after that only what
 * is changed from day to day: the desktop of Linux, how touches act; and which screen keyboard
 * Linux Lab's own settings have chosen, which only Linux Lab changes.
 * The rest waits under "More". What Linux answers is asked again every few seconds, so the
 * screen follows when the person comes back from the settings or from Linux Lab.
 */
public class MainActivity extends Activity {
    private static final long POLL_MS = 2500;
    /** a change of the desktop that has not shown up by then has failed */
    private static final long SWITCH_TIMEOUT_MS = 25000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** looks for a new version of this app and downloads it, apart from the polls */
    private final ExecutorService updater = Executors.newSingleThreadExecutor();
    /** a newer version of this app, once one was found */
    private Updates.Release update;
    private boolean downloading;
    private final Runnable poll = this::poll;
    private boolean resumed;
    private boolean probing;
    /** polls in a row in which the module answered and the switch of the Ubuntu desktop did not */
    private int moduleWithoutDesktop;
    private String switchingTo;
    private long switchingSince;
    /** proot or chroot, while that change is on its way */
    private String engineTo;
    private long engineSince;
    /** su is being asked whether there is root */
    private boolean checkingRoot;
    private int root = Root.UNKNOWN;
    private State last;

    private View updateCard;
    private TextView updateTitle;
    private TextView updateText;
    private Button updateAction;
    private ImageView statusIcon;
    private TextView statusTitle;
    private TextView statusText;
    private Button statusAction;
    private View stepsCard;
    private Ui.Step stepService;
    private Ui.Step stepOverlay;
    private Ui.Step stepInstall;
    private View restrictedHelp;
    private View desktopCard;
    private View desktopSegment;
    private TextView desktopGnome;
    private TextView desktopLxqt;
    private TextView desktopHint;
    private View desktopInstall;
    private View desktopUpdate;
    private View engineBox;
    private View engineSegment;
    private TextView engineProot;
    private TextView engineChroot;
    private TextView engineHint;
    private View controlCard;
    private TextView touchScreen;
    private TextView touchPad;
    private TextView touchHint;
    private TextView keyboardValue;
    private View moreList;

    /** What was found out in one round of asking. */
    private static final class State {
        boolean lab;
        boolean service;
        boolean overlay;
        int[] module;
        String desktop;
        /** proot or chroot; null when the switch of the desktop is older than the chroot mode */
        String engine;
        /** the release serial of the installer that set up the Ubuntu desktop; 0 when older than the serials */
        int serial;
        int verdict;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        Ui.column(findViewById(R.id.scroll), findViewById(R.id.content));

        statusIcon = findViewById(R.id.status_icon);
        statusTitle = findViewById(R.id.status_title);
        statusText = findViewById(R.id.status_text);
        statusAction = findViewById(R.id.status_action);

        stepsCard = findViewById(R.id.steps_card);
        ViewGroup steps = findViewById(R.id.steps);
        stepService = new Ui.Step(steps, 1, R.string.step_service_title, R.string.step_service_text)
            .action(R.string.step_service_action, v -> openServiceSettings());
        link(stepService.extra, R.string.step_restricted_link, v -> {
            restrictedHelp.setVisibility(restrictedHelp.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });
        restrictedHelp = restrictedHelp(stepService.extra);
        stepOverlay = new Ui.Step(steps, 2, R.string.step_overlay_title, R.string.step_overlay_text)
            .action(R.string.step_overlay_action, v -> open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        stepInstall = new Ui.Step(steps, 3, R.string.step_install_title, R.string.step_install_text)
            .action(R.string.step_install_action, v -> install(null));

        desktopCard = findViewById(R.id.desktop_card);
        desktopSegment = findViewById(R.id.desktop_segment);
        desktopGnome = findViewById(R.id.desktop_gnome);
        desktopLxqt = findViewById(R.id.desktop_lxqt);
        desktopHint = findViewById(R.id.desktop_hint);
        desktopInstall = findViewById(R.id.desktop_install);
        desktopGnome.setOnClickListener(v -> switchDesktop("gnome"));
        desktopLxqt.setOnClickListener(v -> switchDesktop("lxqt"));
        desktopInstall.setOnClickListener(v -> install(InstallActivity.UBUNTU));
        desktopUpdate = findViewById(R.id.desktop_update);
        desktopUpdate.setOnClickListener(v -> install(InstallActivity.UPDATE));
        engineBox = findViewById(R.id.engine_box);
        engineSegment = findViewById(R.id.engine_segment);
        engineProot = findViewById(R.id.engine_proot);
        engineChroot = findViewById(R.id.engine_chroot);
        engineHint = findViewById(R.id.engine_hint);
        engineProot.setOnClickListener(v -> switchEngine("proot"));
        engineChroot.setOnClickListener(v -> switchEngine("chroot"));

        controlCard = findViewById(R.id.control_card);
        touchScreen = findViewById(R.id.touch_screen);
        touchPad = findViewById(R.id.touch_pad);
        touchHint = findViewById(R.id.touch_hint);
        touchScreen.setOnClickListener(v -> setTouchpad(false));
        touchPad.setOnClickListener(v -> setTouchpad(true));
        keyboardValue = findViewById(R.id.keyboard_value);

        TextView moreToggle = findViewById(R.id.more_toggle);
        moreList = findViewById(R.id.more_list);
        moreToggle.setOnClickListener(v -> {
            boolean open = moreList.getVisibility() != View.VISIBLE;
            moreList.setVisibility(open ? View.VISIBLE : View.GONE);
            moreToggle.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0,
                open ? R.drawable.ic_chevron_up : R.drawable.ic_chevron_down, 0);
            if (open) {
                ScrollView scroll = findViewById(R.id.scroll);
                scroll.post(() -> scroll.smoothScrollTo(0, moreList.getBottom()));
            }
        });
        findViewById(R.id.more_tile).setOnClickListener(v -> addTile());
        findViewById(R.id.more_reinstall).setOnClickListener(v -> install(null));
        findViewById(R.id.more_remove).setOnClickListener(v -> install(InstallActivity.REMOVE));
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.more_version)).setText(getString(R.string.version, version));
        } catch (Exception e) {
            findViewById(R.id.more_version).setVisibility(View.GONE);
        }

        updateCard = findViewById(R.id.update_card);
        updateTitle = findViewById(R.id.update_title);
        updateText = findViewById(R.id.update_text);
        updateAction = findViewById(R.id.update_action);
        updateAction.setOnClickListener(v -> startUpdate());
        // "adb shell am start -n dev.linuxlab.helper/.MainActivity --ez update_same true" offers the
        // latest release even when it is this very version: the way to try the update path
        lookForUpdate(getIntent().getBooleanExtra("update_same", false));

        // what is known without asking Linux, so that the screen is not empty for the first second
        State known = new State();
        known.lab = Linux.installed(this);
        known.service = serviceEnabled();
        known.overlay = Settings.canDrawOverlays(this);
        known.verdict = TouchService.moduleVerdict();
        render(known);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        poll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        main.removeCallbacks(poll);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
        updater.shutdownNow();
    }

    /** Asks GitHub once per opening of this screen; no network, no card. */
    private void lookForUpdate(boolean same) {
        updater.execute(() -> {
            Updates.Release found;
            try {
                found = Updates.check(this, same);
            } catch (IOException | RuntimeException e) {
                found = null;
            }
            Updates.Release release = found;
            main.post(() -> {
                if (!isDestroyed()) {
                    update = release;
                    renderUpdate();
                }
            });
        });
    }

    private void renderUpdate() {
        updateCard.setVisibility(update != null ? View.VISIBLE : View.GONE);
        if (update == null) {
            return;
        }
        updateTitle.setText(getString(R.string.update_app_title, update.versionName));
        updateText.setText(downloading ? getString(R.string.update_downloading)
            : update.notes.isEmpty() ? getString(R.string.update_text) : update.notes + "\n\n" + getString(R.string.update_text));
        updateAction.setEnabled(!downloading);
    }

    /** Downloads the new version and hands it to Android, which asks the person. */
    private void startUpdate() {
        if (update == null || downloading) {
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, R.string.update_allow, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        downloading = true;
        renderUpdate();
        Updates.Release release = update;
        updater.execute(() -> {
            String error = null;
            try {
                Updates.install(this, Updates.download(this, release));
            } catch (IOException | RuntimeException e) {
                error = String.valueOf(e.getMessage());
            }
            String failure = error;
            main.post(() -> {
                downloading = false;
                if (isDestroyed()) {
                    return;
                }
                renderUpdate();
                if (failure != null) {
                    Toast.makeText(this, getString(R.string.update_failed, failure), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void poll() {
        main.removeCallbacks(poll);
        if (!resumed || probing) {
            return;
        }
        probing = true;
        worker.execute(() -> {
            State state = new State();
            state.lab = Linux.installed(this);
            state.service = serviceEnabled();
            state.overlay = Settings.canDrawOverlays(this);
            state.module = Linux.module();
            String[] desktop = Linux.desktop();
            state.desktop = desktop == null ? null : desktop[0];
            state.engine = desktop == null ? null : desktop[1];
            state.serial = desktop == null ? 0 : parseInt(desktop[2]);
            state.verdict = TouchService.moduleVerdict();
            main.post(() -> {
                probing = false;
                if (isDestroyed()) {
                    return;
                }
                render(state);
                if (resumed) {
                    main.postDelayed(poll, POLL_MS);
                }
            });
        });
    }

    private boolean serviceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(new ComponentName(this, TouchService.class).flattenToString());
    }

    private void render(State s) {
        last = s;
        learn(s);
        boolean installed = s.module != null
            || (Prefs.moduleSeen(this) && s.verdict != TouchService.MODULE_MISSING);
        int left = (s.service ? 0 : 1) + (s.overlay ? 0 : 1) + (installed ? 0 : 1);

        statusAction.setVisibility(View.GONE);
        if (!s.lab) {
            status(R.drawable.ic_status_alert, R.string.status_nolab_title, getString(R.string.status_nolab_text));
            statusButton(R.string.status_nolab_action, v -> open(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)));
        } else if (left > 0) {
            status(R.drawable.ic_status_setup, R.string.status_setup_title,
                getResources().getQuantityString(R.plurals.status_setup_text, left, left));
        } else if (s.module != null) {
            status(R.drawable.ic_status_ok, R.string.status_ok_title,
                getString(Prefs.cursorMode(this) ? R.string.status_ok_pad : R.string.status_ok_screen));
        } else {
            status(R.drawable.ic_status_wait, R.string.status_wait_title, getString(R.string.status_wait_text));
            statusButton(R.string.open_linux_lab, v -> Linux.open(this));
        }

        stepsCard.setVisibility(s.lab && left > 0 ? View.VISIBLE : View.GONE);
        stepService.setDone(s.service);
        stepOverlay.setDone(s.overlay);
        stepInstall.setDone(installed);
        stepInstall.text.setText(s.verdict == TouchService.MODULE_MISSING && Prefs.moduleSeen(this)
            ? R.string.step_install_again : R.string.step_install_text);
        if (s.service) {
            restrictedHelp.setVisibility(View.GONE);
        }

        boolean ready = s.lab && left == 0;
        desktopCard.setVisibility(ready ? View.VISIBLE : View.GONE);
        controlCard.setVisibility(ready ? View.VISIBLE : View.GONE);
        renderDesktop(s);
        renderEngine(s);
        renderControls();
    }

    /** Remembers what Linux said, for the times Linux Lab is closed. */
    private void learn(State s) {
        if (s.module != null) {
            Prefs.setModuleSeen(this, true);
        } else if (s.verdict == TouchService.MODULE_MISSING) {
            Prefs.setModuleSeen(this, false);
        }
        if (s.desktop != null) {
            Prefs.setUbuntu(this, Prefs.YES);
            Prefs.setLastDesktop(this, s.desktop);
            moduleWithoutDesktop = 0;
        } else if (s.module != null) {
            // the switch starts a second after the X server, so one round without it proves nothing
            if (++moduleWithoutDesktop >= 2) {
                Prefs.setUbuntu(this, Prefs.NO);
            }
        } else {
            moduleWithoutDesktop = 0;
        }
    }

    private void status(int icon, int title, String text) {
        statusIcon.setImageResource(icon);
        statusTitle.setText(title);
        statusText.setText(text);
    }

    private void statusButton(int label, View.OnClickListener click) {
        statusAction.setText(label);
        statusAction.setOnClickListener(click);
        statusAction.setVisibility(View.VISIBLE);
    }

    private void renderDesktop(State s) {
        if (switchingTo != null && (switchingTo.equals(s.desktop)
                || SystemClock.uptimeMillis() - switchingSince > SWITCH_TIMEOUT_MS)) {
            switchingTo = null;
        }
        boolean missing = s.desktop == null && Prefs.ubuntu(this) == Prefs.NO;
        desktopSegment.setVisibility(missing ? View.GONE : View.VISIBLE);
        desktopInstall.setVisibility(missing ? View.VISIBLE : View.GONE);
        String shown = switchingTo != null ? switchingTo : s.desktop != null ? s.desktop : Prefs.lastDesktop(this);
        desktopGnome.setSelected("gnome".equals(shown));
        desktopLxqt.setSelected("lxqt".equals(shown));
        boolean usable = switchingTo == null && s.desktop != null;
        desktopGnome.setEnabled(usable);
        desktopLxqt.setEnabled(usable);
        desktopSegment.setAlpha(usable || switchingTo != null ? 1f : 0.5f);
        // set up by an older version of this app (one older than the serials says nothing about it)
        boolean outdated = usable && InstallActivity.bundledSerial(this) > s.serial;
        desktopUpdate.setVisibility(outdated ? View.VISIBLE : View.GONE);
        if (outdated) {
            desktopHint.setText(R.string.desktop_outdated);
        } else if (missing) {
            desktopHint.setText(R.string.desktop_missing);
        } else if (switchingTo != null) {
            desktopHint.setText(R.string.desktop_switching);
        } else if (s.desktop != null) {
            desktopHint.setText(R.string.desktop_hint);
        } else {
            desktopHint.setText(R.string.desktop_closed);
        }
    }

    private void switchDesktop(String name) {
        State s = last;
        if (s == null || s.desktop == null || switchingTo != null || name.equals(s.desktop)) {
            return;
        }
        switchingTo = name;
        switchingSince = SystemClock.uptimeMillis();
        renderDesktop(s);
        worker.execute(() -> {
            String error = Linux.switchDesktop(name);
            main.post(() -> {
                if (error != null && !isDestroyed()) {
                    switchingTo = null;
                    Toast.makeText(this, getString(R.string.desktop_failed, error), Toast.LENGTH_LONG).show();
                    if (last != null) {
                        renderDesktop(last);
                    }
                }
            });
        });
    }

    private void renderEngine(State s) {
        if (engineTo != null && (engineTo.equals(s.engine)
                || SystemClock.uptimeMillis() - engineSince > SWITCH_TIMEOUT_MS)) {
            engineTo = null;
        }
        engineBox.setVisibility(s.engine != null || engineTo != null ? View.VISIBLE : View.GONE);
        String shown = engineTo != null ? engineTo : s.engine;
        engineProot.setSelected("proot".equals(shown));
        engineChroot.setSelected("chroot".equals(shown));
        boolean usable = engineTo == null && !checkingRoot && s.engine != null && switchingTo == null;
        engineProot.setEnabled(usable);
        engineChroot.setEnabled(usable);
        engineSegment.setAlpha(usable || engineTo != null || checkingRoot ? 1f : 0.5f);
        if (checkingRoot) {
            engineHint.setText(R.string.engine_checking);
        } else if (engineTo != null) {
            engineHint.setText(R.string.desktop_switching);
        } else if (root == Root.NONE) {
            engineHint.setText(R.string.engine_no_root);
        } else if (root == Root.DENIED) {
            engineHint.setText(R.string.engine_denied);
        } else if (Prefs.chroot(this) && "proot".equals(s.engine)) {
            engineHint.setText(R.string.engine_fallback);
        } else {
            engineHint.setText("chroot".equals(s.engine) ? R.string.engine_chroot_hint : R.string.engine_proot_hint);
        }
    }

    /**
     * proot: the desktop starts anew under PRoot. chroot: su is asked first (Magisk asks the person),
     * llroot is started, and only then is Linux asked to start the desktop anew in the chroot.
     */
    private void switchEngine(String name) {
        State s = last;
        if (s == null || s.engine == null || engineTo != null || checkingRoot || name.equals(s.engine)) {
            return;
        }
        boolean chroot = "chroot".equals(name);
        checkingRoot = chroot;
        if (!chroot) {
            Prefs.setChroot(this, false);
            TouchService.chrootChanged();
            engineTo = name;
            engineSince = SystemClock.uptimeMillis();
        }
        renderEngine(s);
        worker.execute(() -> {
            int found = chroot ? Root.check(this) : Root.OK;
            boolean started = !chroot || (found == Root.OK && Root.start(this));
            String error = started ? Linux.switchEngine(name) : null;
            main.post(() -> {
                if (isDestroyed()) {
                    return;
                }
                checkingRoot = false;
                if (chroot) {
                    root = found;
                    if (found == Root.OK && !started) {
                        Toast.makeText(this, getString(R.string.desktop_failed, getString(R.string.engine_no_service)),
                            Toast.LENGTH_LONG).show();
                    } else if (started) {
                        Prefs.setChroot(this, true);
                        TouchService.chrootChanged();
                        if (error == null) {
                            engineTo = name;
                            engineSince = SystemClock.uptimeMillis();
                        }
                    }
                }
                if (error != null) {
                    engineTo = null;
                    Toast.makeText(this, getString(R.string.desktop_failed, error), Toast.LENGTH_LONG).show();
                }
                if (last != null) {
                    renderEngine(last);
                }
            });
        });
    }

    private void renderControls() {
        boolean pad = Prefs.cursorMode(this);
        touchScreen.setSelected(!pad);
        touchPad.setSelected(pad);
        touchHint.setText(pad ? R.string.touch_pad_hint : R.string.touch_screen_hint);
        int keyboard = Prefs.androidKeyboard(this);
        keyboardValue.setText(keyboard == Prefs.YES ? R.string.keyboard_android
            : keyboard == Prefs.NO ? R.string.keyboard_linux : R.string.keyboard_unknown);
    }

    private void setTouchpad(boolean on) {
        if (on == Prefs.cursorMode(this)) {
            return;
        }
        Prefs.setCursorMode(this, on);
        // the tile in the shade shows the same
        TileService.requestListeningState(this, new ComponentName(this, ModeTile.class));
        if (last != null) {
            render(last);
        }
    }

    private void install(String what) {
        Intent intent = new Intent(this, InstallActivity.class);
        if (what != null) {
            intent.putExtra(InstallActivity.EXTRA_WHAT, what);
        }
        startActivity(intent);
    }

    /** The page of this very service when the settings have one, else the list of all services. */
    private void openServiceSettings() {
        // the action is not public API; Settings of Android 12 and later let an app open its own service
        Intent details = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, new ComponentName(this, TouchService.class).flattenToString());
        try {
            startActivity(details);
        } catch (ActivityNotFoundException | SecurityException e) {
            open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void addTile() {
        getSystemService(StatusBarManager.class).requestAddTileService(
            new ComponentName(this, ModeTile.class), getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_tile), getMainExecutor(), result -> { });
    }

    private TextView link(LinearLayout parent, int label, View.OnClickListener click) {
        TextView view = new TextView(this, null, 0, R.style.Button_Text);
        view.setText(label);
        view.setOnClickListener(click);
        view.setMinHeight(Math.round(40 * getResources().getDisplayMetrics().density));
        view.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(-Math.round(12 * getResources().getDisplayMetrics().density));
        parent.addView(view, params);
        return view;
    }

    /** Android greys out the switch of a service in an app that was not installed from a store. */
    private View restrictedHelp(LinearLayout parent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setVisibility(View.GONE);
        TextView text = new TextView(this, null, 0, R.style.Text_Secondary);
        text.setText(R.string.step_restricted_help);
        box.addView(text);
        Button details = new Button(this, null, 0, R.style.Button_Secondary);
        details.setText(R.string.step_restricted_open);
        details.setOnClickListener(v -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + getPackageName()))));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Math.round(10 * getResources().getDisplayMetrics().density);
        box.addView(details, params);
        parent.addView(box);
        return box;
    }
}
