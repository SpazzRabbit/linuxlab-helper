package dev.linuxlab.helper;

import android.graphics.Insets;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small pieces both screens share. */
final class Ui {
    /** Text lines longer than this are hard to read on the wide screen of a tablet. */
    private static final int MAX_WIDTH_DP = 640;

    private Ui() {
    }

    /**
     * Keeps @content in a column of readable width in the middle of @scroll, clear of the
     * status bar, the navigation bar and the cut-out. The screen runs under the status bar, so
     * a strip of the background colour lies there over what scrolls by.
     */
    static void column(View scroll, View content) {
        float density = scroll.getResources().getDisplayMetrics().density;
        Insets[] bars = {Insets.NONE};
        View strip = new View(scroll.getContext());
        strip.setBackgroundColor(scroll.getContext().getColor(R.color.background));
        ((ViewGroup) scroll.getParent()).addView(strip, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
        Runnable apply = () -> {
            if (strip.getLayoutParams().height != bars[0].top) {
                strip.getLayoutParams().height = bars[0].top;
                strip.requestLayout();
            }
            int side = Math.round(24 * density);
            int free = (scroll.getWidth() - Math.round(MAX_WIDTH_DP * density)) / 2;
            int left = Math.max(side + bars[0].left, free);
            int right = Math.max(side + bars[0].right, free);
            int top = bars[0].top + Math.round(28 * density);
            int bottom = bars[0].bottom + Math.round(36 * density);
            if (content.getPaddingLeft() != left || content.getPaddingRight() != right
                    || content.getPaddingTop() != top || content.getPaddingBottom() != bottom) {
                content.setPadding(left, top, right, bottom);
            }
        };
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            bars[0] = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            apply.run();
            return WindowInsets.CONSUMED;
        });
        scroll.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) {
                view.post(apply);
            }
        });
    }

    /** One numbered step (see res/layout/item_step.xml). */
    static final class Step {
        final View root;
        final TextView number;
        final TextView title;
        final TextView text;
        final LinearLayout extra;
        final Button button;
        private final int index;

        Step(ViewGroup parent, int index, int title, int text) {
            this.index = index;
            root = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_step, parent, false);
            number = root.findViewById(R.id.step_number);
            this.title = root.findViewById(R.id.step_title);
            this.text = root.findViewById(R.id.step_text);
            extra = root.findViewById(R.id.step_extra);
            button = root.findViewById(R.id.step_button);
            this.title.setText(title);
            this.text.setText(text);
            parent.addView(root);
            setDone(false);
        }

        Step action(int label, View.OnClickListener click) {
            button.setText(label);
            button.setOnClickListener(click);
            button.setVisibility(View.VISIBLE);
            return this;
        }

        /** A done step shows a tick instead of its number and no button. */
        void setDone(boolean done) {
            number.setText(done ? "" : String.valueOf(index));
            number.setBackgroundResource(done ? R.drawable.ic_step_done : R.drawable.step_todo);
            title.setAlpha(done ? 0.6f : 1f);
            text.setVisibility(done ? View.GONE : View.VISIBLE);
            extra.setVisibility(done ? View.GONE : View.VISIBLE);
            if (button.getText().length() > 0) {
                button.setVisibility(done ? View.GONE : View.VISIBLE);
            }
        }
    }
}
