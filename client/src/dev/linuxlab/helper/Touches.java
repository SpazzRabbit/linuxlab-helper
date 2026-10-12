package dev.linuxlab.helper;

import android.view.MotionEvent;

/**
 * Forwards the fingers on one overlay pane to lltouch as they are: every finger is a
 * touch of its own in X, with a begin, updates and an end. Touch-aware programs then
 * scroll, pinch and long-press by themselves; for the others the X server emulates
 * the pointer from the first finger.
 *
 * That emulation only ever follows one finger, so fast typing with two thumbs on a
 * plain on-screen keyboard would lose every key pressed while the previous finger
 * is still down. When a second finger lands on its own (not together with the first,
 * as in a pinch) and the first one is resting, the first touch is ended early.
 */
final class Touches {
    private static final int MAX_POINTERS = 16;
    /** two fingers landing closer in time than this are one gesture, not typing */
    private static final long TOGETHER_MS = 45;

    private final Link link;
    private final Mapper mapper;
    private final float slop;

    private final boolean[] down = new boolean[MAX_POINTERS];
    private final boolean[] moved = new boolean[MAX_POINTERS];
    private final float[] downX = new float[MAX_POINTERS];
    private final float[] downY = new float[MAX_POINTERS];
    private final long[] downTime = new long[MAX_POINTERS];
    private final int[] lastX = new int[MAX_POINTERS];
    private final int[] lastY = new int[MAX_POINTERS];

    Touches(Link link, Mapper mapper, float density) {
        this.link = link;
        this.mapper = mapper;
        this.slop = 8f * density;
    }

    boolean onTouch(MotionEvent ev) {
        float offsetX = ev.getRawX() - ev.getX();
        float offsetY = ev.getRawY() - ev.getY();
        int index = ev.getActionIndex();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancel();
                begin(ev, index, offsetX, offsetY);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                endRestingFinger(ev);
                begin(ev, index, offsetX, offsetY);
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < ev.getPointerCount(); i++) {
                    int id = ev.getPointerId(i);
                    if (id < 0 || id >= MAX_POINTERS || !down[id]) {
                        continue;
                    }
                    float x = ev.getX(i) + offsetX;
                    float y = ev.getY(i) + offsetY;
                    float dx = x - downX[id];
                    float dy = y - downY[id];
                    if (dx * dx + dy * dy > slop * slop) {
                        moved[id] = true;
                    }
                    send(Link.TOUCH_UPDATE, id, x, y, true);
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP: {
                int id = ev.getPointerId(index);
                if (id >= 0 && id < MAX_POINTERS && down[id]) {
                    down[id] = false;
                    send(Link.TOUCH_END, id, ev.getX(index) + offsetX, ev.getY(index) + offsetY, false);
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                cancel();
                break;
            default:
                break;
        }
        return true;
    }

    /** End every touch that is still down; called when the overlay goes away. */
    void cancel() {
        for (int id = 0; id < MAX_POINTERS; id++) {
            if (down[id]) {
                down[id] = false;
                link.send(Link.TOUCH_END, id, lastX[id], lastY[id]);
            }
        }
    }

    private void begin(MotionEvent ev, int index, float offsetX, float offsetY) {
        int id = ev.getPointerId(index);
        if (id < 0 || id >= MAX_POINTERS) {
            return;
        }
        downX[id] = ev.getX(index) + offsetX;
        downY[id] = ev.getY(index) + offsetY;
        downTime[id] = ev.getEventTime();
        moved[id] = false;
        down[id] = true;
        send(Link.TOUCH_BEGIN, id, downX[id], downY[id], false);
    }

    private void endRestingFinger(MotionEvent ev) {
        int only = -1;
        for (int id = 0; id < MAX_POINTERS; id++) {
            if (down[id]) {
                if (only >= 0) {
                    return; // already a multi-finger gesture
                }
                only = id;
            }
        }
        if (only >= 0 && !moved[only] && ev.getEventTime() - downTime[only] >= TOGETHER_MS) {
            down[only] = false;
            link.send(Link.TOUCH_END, only, lastX[only], lastY[only]);
        }
    }

    private void send(int type, int id, float x, float y, boolean skipSame) {
        long p = mapper.toRoot(x, y);
        if (p < 0) {
            return;
        }
        int rx = (int) (p >> 16);
        int ry = (int) (p & 0xffff);
        if (skipSame && rx == lastX[id] && ry == lastY[id]) {
            return;
        }
        lastX[id] = rx;
        lastY[id] = ry;
        link.send(type, id, rx, ry);
    }
}
