package dev.linuxlab.testime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;

/**
 * A keyboard without keys: it types what a broadcast tells it to, through the same calls a real
 * keyboard makes. It exists to test, from adb, that text typed on the Android keyboard reaches
 * Linux: Android only lets the current keyboard write into the text field in focus.
 *
 *   adb shell am broadcast -a dev.linuxlab.testime.DO --es op commit --es b64 <text in base64>
 *
 * op: commit (text), compose (text being composed), finish (the composed text becomes text),
 *     delete (--ei n characters before the cursor), key (--ei n key code, down and up)
 */
public class TestIme extends InputMethodService {
    private static final String TAG = "TestIme";
    private static final String ACTION = "dev.linuxlab.testime.DO";

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            type(intent);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        registerReceiver(receiver, new IntentFilter(ACTION), Context.RECEIVER_EXPORTED);
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(receiver);
        super.onDestroy();
    }

    @Override
    public View onCreateInputView() {
        TextView view = new TextView(this);
        view.setText("Linux Lab test keyboard");
        view.setTextColor(Color.WHITE);
        view.setBackgroundColor(Color.DKGRAY);
        view.setGravity(Gravity.CENTER);
        view.setHeight(Math.round(120 * getResources().getDisplayMetrics().density));
        return view;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        Log.i(TAG, "field of " + attribute.packageName + ", input type 0x" + Integer.toHexString(attribute.inputType));
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        Log.i(TAG, "shown");
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        Log.i(TAG, "hidden");
    }

    private void type(Intent intent) {
        String op = intent.getStringExtra("op");
        String text = intent.hasExtra("b64")
            ? new String(Base64.decode(intent.getStringExtra("b64"), Base64.DEFAULT), StandardCharsets.UTF_8)
            : intent.getStringExtra("text");
        int n = intent.getIntExtra("n", 1);
        InputConnection field = getCurrentInputConnection();
        if (field == null || op == null) {
            Log.w(TAG, op + ": no text field");
            return;
        }
        boolean ok;
        switch (op) {
            case "commit":
                ok = field.commitText(text, 1);
                break;
            case "compose":
                ok = field.setComposingText(text, 1);
                break;
            case "finish":
                ok = field.finishComposingText();
                break;
            case "delete":
                ok = field.deleteSurroundingText(n, 0);
                break;
            case "key":
                ok = field.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, n))
                    && field.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, n));
                break;
            default:
                ok = false;
                break;
        }
        Log.i(TAG, op + (ok ? " done" : " refused"));
    }
}
