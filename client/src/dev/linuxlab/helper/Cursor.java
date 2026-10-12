package dev.linuxlab.helper;

import android.view.MotionEvent;

/**
 * Cursor mode: the screen works as a touchpad. The overlay keeps the pointer position
 * itself and sends it to lltouch together with the button mask.
 *
 *   one finger moving             the cursor moves, faster fingers move it further
 *   tap                           left click where the cursor is
 *   tap with two fingers          right click; with three, middle click
 *   two fingers moving            wheel scrolling, the content follows the fingers
 *   tap, then touch again at once the left button is held until the finger lifts: a
 *                                 quick second tap is a double click, a move is a drag
 */
final class Cursor {
    static final int LEFT = 1;
    static final int MIDDLE = 2;
    static final int RIGHT = 4;
    private static final int WHEEL_UP = 8;
    private static final int WHEEL_DOWN = 16;
    private static final int WHEEL_LEFT = 32;
    private static final int WHEEL_RIGHT = 64;

    private static final long TAP_MS = 250;
    private static final long DRAG_AFTER_TAP_MS = 300;
    private static final float GAIN = 1.3f;

    private final Link link;
    private final Mapper mapper;
    private final float slop;
    private final float wheelStep;
    private final float accelerationSpan;

    private float x = -1;
    private float y = -1;
    private int rootWidth;
    private int rootHeight;
    private int held;

    private int primary = -1;
    private float lastX;
    private float lastY;
    private float travel;
    private long downTime;
    private int fingers;
    private float lastFocusX;
    private float lastFocusY;
    private float wheelX;
    private float wheelY;
    private boolean scrolled;
    private long lastTapUp;

    Cursor(Link link, Mapper mapper, float density) {
        this.link = link;
        this.mapper = mapper;
        this.slop = 8f * density;
        this.wheelStep = 18f * density;
        this.accelerationSpan = 12f * density;
    }

    boolean onTouch(MotionEvent ev) {
        place();
        int index = ev.getActionIndex();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                release();
                primary = ev.getPointerId(index);
                lastX = ev.getX(index);
                lastY = ev.getY(index);
                travel = 0;
                fingers = 1;
                scrolled = false;
                downTime = ev.getEventTime();
                if (downTime - lastTapUp < DRAG_AFTER_TAP_MS) {
                    held = LEFT;
                    send();
                }
                lastTapUp = 0;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                fingers = Math.max(fingers, ev.getPointerCount());
                lastFocusX = focusX(ev, -1);
                lastFocusY = focusY(ev, -1);
                wheelX = 0;
                wheelY = 0;
                break;
            case MotionEvent.ACTION_MOVE:
                if (ev.getPointerCount() >= 2) {
                    scroll(ev);
                } else if (ev.findPointerIndex(primary) == 0 || ev.getPointerCount() == 1) {
                    move(ev.getX(0), ev.getY(0));
                }
                break;
            case MotionEvent.ACTION_POINTER_UP: {
                // the finger that stays becomes the one that moves the cursor
                int stays = index == 0 ? 1 : 0;
                if (ev.getPointerCount() == 2) {
                    primary = ev.getPointerId(stays);
                    lastX = ev.getX(stays);
                    lastY = ev.getY(stays);
                } else {
                    lastFocusX = focusX(ev, index);
                    lastFocusY = focusY(ev, index);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
                if (held != 0) {
                    release();
                    if (travel <= slop) {
                        lastTapUp = 0; // the second click of a double click
                    }
                } else if (!scrolled && travel <= slop && ev.getEventTime() - downTime < TAP_MS) {
                    click(fingers == 1 ? LEFT : fingers == 2 ? RIGHT : MIDDLE);
                    if (fingers == 1) {
                        lastTapUp = ev.getEventTime();
                    }
                }
                primary = -1;
                break;
            case MotionEvent.ACTION_CANCEL:
                cancel();
                break;
            default:
                break;
        }
        return true;
    }

    /** Let go of any button; called when the overlay goes away or the gesture is cancelled. */
    void cancel() {
        release();
        primary = -1;
    }

    private void move(float nx, float ny) {
        float dx = nx - lastX;
        float dy = ny - lastY;
        lastX = nx;
        lastY = ny;
        float step = (float) Math.hypot(dx, dy);
        travel += step;
        if (step == 0 || (travel <= slop && held == 0)) {
            return; // a tap may wobble without moving the cursor
        }
        float gain = GAIN * (1f + Math.min(step / accelerationSpan, 2f)) * mapper.scale();
        x = Math.max(0, Math.min(rootWidth - 1, x + dx * gain));
        y = Math.max(0, Math.min(rootHeight - 1, y + dy * gain));
        send();
    }

    private void scroll(MotionEvent ev) {
        float fx = focusX(ev, -1);
        float fy = focusY(ev, -1);
        wheelX += fx - lastFocusX;
        wheelY += fy - lastFocusY;
        lastFocusX = fx;
        lastFocusY = fy;
        while (Math.abs(wheelY) >= wheelStep) {
            wheel(wheelY > 0 ? WHEEL_UP : WHEEL_DOWN);
            wheelY -= Math.signum(wheelY) * wheelStep;
        }
        while (Math.abs(wheelX) >= wheelStep) {
            wheel(wheelX > 0 ? WHEEL_LEFT : WHEEL_RIGHT);
            wheelX -= Math.signum(wheelX) * wheelStep;
        }
    }

    private void wheel(int bit) {
        scrolled = true;
        link.send(Link.POINTER, held | bit, Math.round(x), Math.round(y));
        send();
    }

    private void click(int button) {
        link.send(Link.POINTER, button, Math.round(x), Math.round(y));
        send();
    }

    private void release() {
        if (held != 0) {
            held = 0;
            send();
        }
    }

    private void send() {
        link.send(Link.POINTER, held, Math.round(x), Math.round(y));
    }

    /** Start in the middle of the screen, and stay inside it when its size changes. */
    private void place() {
        int w = mapper.rootWidth();
        int h = mapper.rootHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        if (x < 0 || w != rootWidth || h != rootHeight) {
            rootWidth = w;
            rootHeight = h;
            x = x < 0 ? w / 2f : Math.min(x, w - 1);
            y = y < 0 ? h / 2f : Math.min(y, h - 1);
        }
    }

    private static float focusX(MotionEvent ev, int skipIndex) {
        float sum = 0;
        int n = 0;
        for (int i = 0; i < ev.getPointerCount(); i++) {
            if (i != skipIndex) {
                sum += ev.getX(i);
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    private static float focusY(MotionEvent ev, int skipIndex) {
        float sum = 0;
        int n = 0;
        for (int i = 0; i < ev.getPointerCount(); i++) {
            if (i != skipIndex) {
                sum += ev.getY(i);
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }
}
