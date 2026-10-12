package dev.linuxlab.helper;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.RegionIterator;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.AttachedSurfaceControl;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import org.json.JSONObject;

import java.util.List;

/**
 * Puts a transparent layer over the picture of Linux Lab and sends the touches on it
 * to lltouch, the module inside the X server of Linux Lab. The stock app never sees
 * them, so its tap delay is gone.
 *
 * The layer is an ordinary application overlay: the notification shade, the taskbar
 * and the Android keyboard are above it and keep working. It is one full-screen
 * window that only takes touches inside a region: the SurfaceView of Linux Lab, minus
 * the settings button of the stock app, minus every window that lies above Linux Lab.
 * The accessibility service is only used to learn where those windows are.
 *
 * Nothing is covered while the module does not answer or while another window has
 * the focus: the first tap then goes to Linux Lab itself and gives it the focus.
 *
 * Two more things only Android has go to the desktop over a second link (see Host): the
 * charge of the battery, and what is typed on the Android keyboard. For the keyboard the
 * layer is a text field: when the desktop asks for a keyboard, the layer takes the input
 * focus and the keyboard writes into it, in whatever language, and every piece of text
 * is passed on. The stock app has no text field, so its keyboard can only send key codes.
 *
 * The third thing is how the tablet is held. The window of Linux Lab is a landscape one
 * whatever happens: Android turns it upside down with the tablet, never upright. What Android
 * has not turned is told to the desktop, which turns its own monitor inside the window.
 */
public class TouchService extends AccessibilityService implements Link.Listener, Host.Listener, Mapper {
    static final String LAYER_TITLE = "Linux Lab Helper layer";
    private static final String TAG = "LinuxLabHelper";
    private static final String TARGET = "com.hihonor.pcengine";
    private static final String HOLE_ID = TARGET + ":id/floating_ball";

    private static volatile TouchService instance;

    /** What the service has seen of the touch module (see refresh): nothing yet, answers, missing. */
    static final int MODULE_UNKNOWN = 0;
    static final int MODULE_OK = 1;
    static final int MODULE_MISSING = 2;
    /** a picture of Linux this long without an answer of the module: the module is not there */
    private static final long MODULE_WAIT_MS = 20000;
    private static volatile int moduleVerdict = MODULE_UNKNOWN;
    private long pictureSince;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refresh;
    private final Region covered = new Region();
    private Context overlayContext;
    private WindowManager windows;
    private Link link;
    private Host host;
    private Touches touches;
    private Cursor cursor;
    private View layer;
    private Rect surface;
    private boolean cursorGesture;
    private WindowManager.LayoutParams layerParams;
    private boolean keyboardOn;
    private boolean keyboardSeen;
    private Intent battery;
    private String sentBattery;
    private OrientationEventListener tilt;
    /** How the tablet is held: 0, 90, 180 or 270 degrees clockwise from its natural position; -1 unknown. */
    private int held = -1;
    private int sentTurn = -1;
    private boolean onScreen;
    private final Runnable turnSoon = () -> sendTurn(false);
    private final Runnable turnLate = () -> sendTurn(true);
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int id) {
        }

        @Override
        public void onDisplayRemoved(int id) {
        }

        @Override
        public void onDisplayChanged(int id) {
            // Android turned its picture: what is left to the desktop is different now
            if (id == Display.DEFAULT_DISPLAY) {
                scheduleTurn();
            }
        }
    };
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            battery = intent;
            sendBattery();
        }
    };

    /** Whether Linux had the touch module the last time Linux Lab showed its picture. */
    static int moduleVerdict() {
        return moduleVerdict;
    }

    /** Size of the Linux screen when the service is connected to the module right now, else null. */
    static int[] connectedScreen() {
        TouchService service = instance;
        if (service == null || service.link == null || !service.link.isConnected()) {
            return null;
        }
        return new int[] {service.link.rootWidth(), service.link.rootHeight()};
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        float density = getResources().getDisplayMetrics().density;
        link = new Link(this);
        host = new Host(this);
        touches = new Touches(link, this, density);
        cursor = new Cursor(link, this, density);
        // the charge is a sticky broadcast: the current one comes back at once
        battery = registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            Context.RECEIVER_NOT_EXPORTED);
        tilt = new OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
            @Override
            public void onOrientationChanged(int degrees) {
                onTilt(degrees);
            }
        };
        getSystemService(DisplayManager.class).registerDisplayListener(displayListener, main);
        scheduleRefresh();
        main.post(keepRoot);
    }

    /**
     * In the chroot mode llroot has to be there before Linux Lab starts its desktop (startlxqt waits
     * for it a few seconds, then starts the desktop under PRoot): it is started with this service,
     * which Android brings up after a reboot, and looked after every minute.
     */
    private final Runnable keepRoot = new Runnable() {
        @Override
        public void run() {
            main.removeCallbacks(this);
            if (instance == null) {
                return;
            }
            if (Prefs.chroot(TouchService.this)) {
                new Thread(() -> {
                    if (!Root.answers() && !Root.start(TouchService.this)) {
                        Log.w(TAG, "llroot did not start");
                    }
                }, "keep-root").start();
            }
            main.postDelayed(this, 60_000);
        }
    };

    /** The chroot mode was switched on or off on the settings screen. */
    static void chrootChanged() {
        TouchService service = instance;
        if (service != null) {
            service.main.post(service.keepRoot);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        scheduleRefresh();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        instance = null;
        main.removeCallbacks(keepRoot);
        detach();
        if (link != null) {
            link.stop();
        }
        if (host != null) {
            host.stop();
            unregisterReceiver(batteryReceiver);
            tilt.disable();
            getSystemService(DisplayManager.class).unregisterDisplayListener(displayListener);
        }
        super.onDestroy();
    }

    @Override
    public void onLinkState(boolean connected) {
        Log.i(TAG, "module " + (connected ? "connected" : "disconnected"));
        scheduleRefresh();
    }

    @Override
    public void onHostState(boolean connected) {
        main.post(() -> {
            Log.i(TAG, "desktop " + (connected ? "connected" : "disconnected"));
            if (connected) {
                host.send("hello 2");
                sentBattery = null;
                sendBattery();
                sentTurn = -1;
                if (tilt.canDetectOrientation()) {
                    tilt.enable();
                }
                scheduleTurn();
            } else {
                hideKeyboard();
                tilt.disable();
            }
        });
    }

    @Override
    public void onKeyboardWanted(int state) {
        // Linux asks only while the keyboard of Android is the one chosen
        main.post(() -> {
            if (state == 1 || (state == 2 && !keyboardOn)) {
                showKeyboard();
            } else {
                hideKeyboard();
            }
        });
    }

    @Override
    public void onKeyboardChosen(boolean android) {
        main.post(() -> {
            Prefs.setAndroidKeyboard(this, android);
            if (!android) {
                hideKeyboard();
            }
        });
    }

    private void onTilt(int degrees) {
        if (degrees == OrientationEventListener.ORIENTATION_UNKNOWN) {
            return; // lying flat: held as it was
        }
        if (held >= 0) {
            int off = Math.abs(degrees - held);
            // a turn counts when it is 15 degrees past the diagonal, so the edge does not flicker
            if (Math.min(off, 360 - off) < 60) {
                return;
            }
        }
        int nearest = (degrees + 45) / 90 * 90 % 360;
        if (nearest != held) {
            held = nearest;
            scheduleTurn();
        }
    }

    private void scheduleTurn() {
        main.removeCallbacks(turnSoon);
        main.removeCallbacks(turnLate);
        main.postDelayed(turnSoon, 500);
    }

    /**
     * Says how far the picture still has to be turned clockwise to stand upright. Android
     * turns its picture against the turn of the tablet, by Display.getRotation(); what it has
     * not turned is left to the desktop. Upside down is Android's to do, with an animation
     * that takes a while: half a turn is only passed on if it is still wanted two seconds later.
     */
    private void sendTurn(boolean late) {
        if (!onScreen || host == null || !host.isConnected()) {
            return;
        }
        Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        // a tablet that has lain flat since the start is taken to be held the way Android shows it
        int turn = held < 0 ? 0 : (720 - held - display.getRotation() * 90) % 360;
        if (turn == sentTurn) {
            return;
        }
        if (turn == 180 && !late) {
            main.postDelayed(turnLate, 2000);
            return;
        }
        sentTurn = turn;
        host.send("rotation " + turn);
    }

    private void sendBattery() {
        Intent now = battery;
        if (now == null || host == null || !host.isConnected()) {
            return;
        }
        int level = now.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = now.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) {
            return;
        }
        int status = now.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        boolean plugged = now.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        long toFull = getSystemService(BatteryManager.class).computeChargeTimeRemaining();
        String line = "battery " + (level * 100 / scale) + " " + status + " " + (plugged ? 1 : 0) + " "
            + (toFull < 0 ? -1 : toFull / 1000);
        if (!line.equals(sentBattery)) {
            sentBattery = line;
            host.send(line);
        }
    }

    /**
     * The keyboard of Android writes into the window that has the input focus, so the layer
     * takes it for as long as the keyboard is up, and gives it back to Linux Lab after.
     */
    private void showKeyboard() {
        if (layer == null || keyboardOn) {
            return;
        }
        Configuration config = getResources().getConfiguration();
        if (config.keyboard != Configuration.KEYBOARD_NOKEYS
                && config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO) {
            return; // a real keyboard is attached: it types through Linux Lab itself
        }
        keyboardOn = true;
        keyboardSeen = false;
        layerParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        layerParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        layerParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        windows.updateViewLayout(layer, layerParams);
        layer.requestFocus();
        final View view = layer;
        // the window has to have the focus first
        view.postDelayed(() -> {
            if (keyboardOn && layer == view) {
                overlayContext.getSystemService(InputMethodManager.class).showSoftInput(view, 0);
            }
        }, 150);
        Log.i(TAG, "keyboard asked for");
    }

    private void hideKeyboard() {
        if (!keyboardOn) {
            return;
        }
        keyboardOn = false;
        if (layer != null) {
            overlayContext.getSystemService(InputMethodManager.class)
                .hideSoftInputFromWindow(layer.getWindowToken(), 0);
            layerParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            layerParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED;
            windows.updateViewLayout(layer, layerParams);
        }
        Log.i(TAG, "keyboard let go");
    }

    /** A key as the desktop knows it, or null for a key that makes a character. */
    private static String keyName(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DEL:
                return "BackSpace";
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return "Delete";
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return "Return";
            case KeyEvent.KEYCODE_TAB:
                return "Tab";
            case KeyEvent.KEYCODE_ESCAPE:
                return "Escape";
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return "Left";
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return "Right";
            case KeyEvent.KEYCODE_DPAD_UP:
                return "Up";
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return "Down";
            case KeyEvent.KEYCODE_MOVE_HOME:
                return "Home";
            case KeyEvent.KEYCODE_MOVE_END:
                return "End";
            case KeyEvent.KEYCODE_PAGE_UP:
                return "Page_Up";
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return "Page_Down";
            default:
                return null;
        }
    }

    /** Keys the keyboard sends as keys: the ones that edit, and characters when it prefers so. */
    private boolean forwardKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_MULTIPLE && event.getCharacters() != null) {
            sendText(event.getCharacters());
            return true;
        }
        String name = keyName(event.getKeyCode());
        if (name != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                host.send("key " + name);
            }
            return true;
        }
        if (event.isSystem() || KeyEvent.isModifierKey(event.getKeyCode())) {
            return false;
        }
        int character = event.getUnicodeChar();
        if (character <= 0 || Character.isISOControl(character)) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            sendText(new String(Character.toChars(character)));
        }
        return true;
    }

    /** Text goes as text, line ends as the Return key: that is what they are in a terminal and a form. */
    private void sendText(String text) {
        int from = 0;
        for (int end; (end = text.indexOf('\n', from)) >= 0; from = end + 1) {
            if (end > from) {
                host.send("text " + JSONObject.quote(text.substring(from, end)));
            }
            host.send("key Return");
        }
        if (from < text.length()) {
            host.send("text " + JSONObject.quote(text.substring(from)));
        }
    }

    /** The layer: it draws nothing, takes the touches, and is a text field for the keyboard. */
    private final class LayerView extends View {
        LayerView(Context context) {
            super(context);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        @Override
        public boolean onCheckIsTextEditor() {
            return true;
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo out) {
            out.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
            out.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_ENTER_ACTION;
            out.initialSelStart = 0;
            out.initialSelEnd = 0;
            return new Typing(this);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            // "back" with the keyboard already down: the user is done typing
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    hideKeyboard();
                }
                return true;
            }
            return forwardKey(event) || super.dispatchKeyEvent(event);
        }
    }

    /**
     * What the keyboard does to the "text field". The text itself lives in Linux and is not
     * known here, so the keyboard is told that the field is empty around the cursor: it then
     * deletes with the backspace key and corrects only the word it is composing.
     */
    private final class Typing extends BaseInputConnection {
        private String composing = "";

        Typing(View view) {
            super(view, false);
        }

        private void dropComposing() {
            if (!composing.isEmpty()) {
                composing = "";
                host.send("preedit \"\"");
            }
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            dropComposing();
            sendText(text.toString());
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            composing = text.toString();
            host.send("preedit " + JSONObject.quote(composing));
            return true;
        }

        @Override
        public boolean finishComposingText() {
            if (!composing.isEmpty()) {
                String text = composing;
                dropComposing();
                sendText(text);
            }
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            host.send("delete " + Math.max(0, beforeLength) + " " + Math.max(0, afterLength));
            return true;
        }

        @Override
        public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
            return deleteSurroundingText(beforeLength, afterLength);
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            forwardKey(event);
            return true;
        }

        @Override
        public boolean performEditorAction(int actionCode) {
            host.send("key Return");
            return true;
        }

        @Override
        public CharSequence getTextBeforeCursor(int length, int flags) {
            return "";
        }

        @Override
        public CharSequence getTextAfterCursor(int length, int flags) {
            return "";
        }

        @Override
        public CharSequence getSelectedText(int flags) {
            return null;
        }

        @Override
        public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            ExtractedText text = new ExtractedText();
            text.text = "";
            return text;
        }

        @Override
        public int getCursorCapsMode(int reqModes) {
            return 0;
        }

        @Override
        public boolean setComposingRegion(int start, int end) {
            return true;
        }

        @Override
        public boolean setSelection(int start, int end) {
            return true;
        }
    }

    @Override
    public long toRoot(float rawX, float rawY) {
        Rect s = surface;
        int w = link.rootWidth();
        int h = link.rootHeight();
        if (s == null || s.width() <= 0 || s.height() <= 0 || w <= 0 || h <= 0) {
            return -1;
        }
        int x = Math.round((rawX - s.left) * w / s.width());
        int y = Math.round((rawY - s.top) * h / s.height());
        x = Math.max(0, Math.min(w - 1, x));
        y = Math.max(0, Math.min(h - 1, y));
        return ((long) x << 16) | y;
    }

    @Override
    public float scale() {
        Rect s = surface;
        return s == null || s.width() <= 0 ? 0 : (float) link.rootWidth() / s.width();
    }

    @Override
    public int rootWidth() {
        return link.rootWidth();
    }

    @Override
    public int rootHeight() {
        return link.rootHeight();
    }

    private void scheduleRefresh() {
        main.removeCallbacks(refresh);
        main.postDelayed(refresh, 60);
    }

    private void refresh() {
        Rect found = null;
        Region region = null;
        boolean focused = false;
        boolean starting = false;
        try {
            // the list goes from the topmost window down
            List<AccessibilityWindowInfo> all = getWindows();
            AccessibilityWindowInfo target = null;
            AccessibilityNodeInfo root = null;
            for (AccessibilityWindowInfo window : all) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                    continue;
                }
                AccessibilityNodeInfo node = window.getRoot();
                if (node != null && node.getPackageName() != null && TARGET.contentEquals(node.getPackageName())) {
                    AccessibilityNodeInfo view = findSurface(node);
                    // while Linux Lab is still loading there is no surface yet
                    starting = view == null;
                    if (view != null) {
                        target = window;
                        root = node;
                        found = new Rect();
                        view.getBoundsInScreen(found);
                        break;
                    }
                }
            }
            if (target != null && !found.isEmpty()) {
                focused = target.isFocused() || target.isActive();
                if (!focused && keyboardOn) {
                    // with the keyboard up the layer itself holds the input focus
                    for (AccessibilityWindowInfo window : all) {
                        if (window.isFocused() && isOwnLayer(window)) {
                            focused = true;
                            break;
                        }
                    }
                }
                region = new Region(found);
                List<AccessibilityNodeInfo> balls = root.findAccessibilityNodeInfosByViewId(HOLE_ID);
                if (balls != null && !balls.isEmpty()) {
                    Rect hole = new Rect();
                    balls.get(0).getBoundsInScreen(hole);
                    int margin = Math.round(8 * getResources().getDisplayMetrics().density);
                    hole.inset(-margin, -margin);
                    region.op(hole, Region.Op.DIFFERENCE);
                }
                Region above = new Region();
                for (AccessibilityWindowInfo window : all) {
                    if (window.getLayer() <= target.getLayer() || isOwnLayer(window)) {
                        continue;
                    }
                    window.getRegionInScreen(above);
                    region.op(above, Region.Op.DIFFERENCE);
                }
                leavePeepholes(region);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "window scan failed", e);
        }
        link.setWanted(found != null);
        host.setWanted(found != null);
        // Linux Lab after a reset or a reinstall has a fresh Linux without the module: the settings
        // screen says so instead of only "Linux Lab is closed"
        if (found == null) {
            pictureSince = 0;
        } else if (link.isConnected()) {
            moduleVerdict = MODULE_OK;
            pictureSince = SystemClock.uptimeMillis();
        } else {
            long now = SystemClock.uptimeMillis();
            if (pictureSince == 0) {
                pictureSince = now;
            }
            if (now - pictureSince >= MODULE_WAIT_MS) {
                moduleVerdict = MODULE_MISSING;
            } else {
                main.postDelayed(refresh, MODULE_WAIT_MS + 500 - (now - pictureSince));
            }
        }
        if (onScreen != (found != null && focused)) {
            onScreen = !onScreen;
            if (onScreen) {
                scheduleTurn();
            }
        }
        if (starting) {
            // the desktop replaces the loading screen inside the same window, and that
            // sends none of the events the service listens to: look again a bit later
            main.removeCallbacks(refresh);
            main.postDelayed(refresh, 1000);
        }
        if (region == null || region.isEmpty() || !focused || !link.isConnected() || !Settings.canDrawOverlays(this)) {
            detach();
            return;
        }
        surface = found;
        attach(region);
    }

    /**
     * A window under the layer is listed only while some of the space over it is free, and the
     * system checks that by bounding boxes. One free pixel on each side of the region keeps its
     * whole bounding box in play, so whatever comes to lie over the picture under the layer (the
     * caption with the window buttons, a floating window of another app) is listed, and the
     * layer steps aside for it.
     */
    private static void leavePeepholes(Region region) {
        if (region.isEmpty()) {
            return;
        }
        Rect bounds = region.getBounds();
        Region holes = new Region();
        Rect rect = new Rect();
        boolean top = false;
        boolean left = false;
        boolean right = false;
        boolean bottom = false;
        RegionIterator rects = new RegionIterator(region);
        while (rects.next(rect)) {
            if (!top && rect.top == bounds.top) {
                top = holes.op(rect.left, rect.top, rect.left + 1, rect.top + 1, Region.Op.UNION);
            }
            if (!left && rect.left == bounds.left) {
                left = holes.op(rect.left, rect.bottom - 1, rect.left + 1, rect.bottom, Region.Op.UNION);
            }
            if (!right && rect.right == bounds.right) {
                right = holes.op(rect.right - 1, rect.top, rect.right, rect.top + 1, Region.Op.UNION);
            }
            if (!bottom && rect.bottom == bounds.bottom) {
                bottom = holes.op(rect.right - 1, rect.bottom - 1, rect.right, rect.bottom, Region.Op.UNION);
            }
        }
        region.op(holes, Region.Op.DIFFERENCE);
    }

    /**
     * Our own layer is in the list too, above Linux Lab. Taking it for a foreign window
     * would cut the region by its own previous value, back and forth for ever.
     */
    private boolean isOwnLayer(AccessibilityWindowInfo window) {
        CharSequence title = window.getTitle();
        if (title != null && LAYER_TITLE.contentEquals(title)) {
            return true;
        }
        AccessibilityNodeInfo root = window.getRoot();
        if (root != null && root.getPackageName() != null && getPackageName().contentEquals(root.getPackageName())) {
            return true;
        }
        // seen on MagicOS: the overlay is listed as a system window with neither title nor content
        if (layer != null && window.getType() == AccessibilityWindowInfo.TYPE_SYSTEM && !covered.isEmpty()) {
            Region theirs = new Region();
            window.getRegionInScreen(theirs);
            return theirs.equals(covered);
        }
        return false;
    }

    /** The view the desktop is drawn on: the only SurfaceView of the Linux Lab activity. */
    private static AccessibilityNodeInfo findSurface(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        CharSequence cls = node.getClassName();
        if (cls != null && cls.toString().endsWith("SurfaceView")) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo hit = findSurface(node.getChild(i));
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /**
     * The window manager of an accessibility service puts every window on the token of the service,
     * and that token lives in the accessibility layer: above the status bar, the shade and the
     * navigation, whatever type the window asks for. A window context of the overlay type has a
     * token of its own, in the layer the type belongs to.
     */
    private boolean prepareWindows() {
        if (windows != null) {
            return true;
        }
        try {
            Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            overlayContext = createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            windows = overlayContext.getSystemService(WindowManager.class);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "no overlay context", e);
            overlayContext = null;
            return false;
        }
    }

    private void attach(Region region) {
        if (layer == null) {
            if (!prepareWindows()) {
                return;
            }
            View view = new LayerView(overlayContext);
            view.setOnTouchListener(this::onLayerTouch);
            // the user sent the keyboard away (the back gesture): the focus goes back to Linux Lab
            view.setOnApplyWindowInsetsListener((v, insets) -> {
                boolean visible = insets.isVisible(WindowInsets.Type.ime());
                if (keyboardSeen && !visible && keyboardOn) {
                    hideKeyboard();
                }
                keyboardSeen = visible;
                return insets;
            });
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setFitInsetsTypes(0);
            // Android drops a touch that reaches another app through the frame of an opaque window
            // of ours, even through a gap in the touchable region. The layer draws nothing, so its
            // opacity only matters to that rule: keep it under the limit (0.8).
            lp.alpha = 0.5f;
            lp.setTitle(LAYER_TITLE);
            view.setContentDescription(LAYER_TITLE);
            try {
                windows.addView(view, lp);
            } catch (RuntimeException e) {
                Log.w(TAG, "layer not added", e);
                return;
            }
            layer = view;
            layerParams = lp;
            keyboardOn = false;
            covered.setEmpty();
            Log.i(TAG, "layer on");
        }
        if (region.equals(covered) && layer.isAttachedToWindow()) {
            return;
        }
        final Region wanted = new Region(region);
        // the surface control only exists once the view has a window
        layer.post(() -> applyRegion(wanted));
    }

    private void applyRegion(Region region) {
        if (layer == null) {
            return;
        }
        AttachedSurfaceControl control = layer.getRootSurfaceControl();
        if (control == null) {
            layer.postDelayed(() -> applyRegion(region), 30);
            return;
        }
        int[] origin = new int[2];
        layer.getLocationOnScreen(origin);
        Region local = new Region(region);
        local.translate(-origin[0], -origin[1]);
        control.setTouchableRegion(local);
        covered.set(region);
        Log.i(TAG, "covers " + region.getBounds() + (region.isRect() ? "" : " with gaps"));
    }

    /**
     * The layer only ever gets touches that land on the picture of Linux Lab: what is
     * above it (status bar, shade, taskbar, other windows) is outside the touchable
     * region and is handled by Android as usual. So there is nothing to decide here
     * but the mode.
     */
    private boolean onLayerTouch(View view, MotionEvent ev) {
        // one gesture goes one way from its first finger to its last
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            cursorGesture = Prefs.cursorMode(this);
        }
        return cursorGesture ? cursor.onTouch(ev) : touches.onTouch(ev);
    }

    private void detach() {
        if (layer == null) {
            return;
        }
        touches.cancel();
        cursor.cancel();
        try {
            windows.removeView(layer);
        } catch (RuntimeException e) {
            Log.w(TAG, "layer not removed", e);
        }
        layer = null;
        layerParams = null;
        keyboardOn = false;
        surface = null;
        covered.setEmpty();
        Log.i(TAG, "layer off");
    }
}
