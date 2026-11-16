package com.kullanim.takip;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** Bağımlılıksız, çok hafif sütun grafiği. */
final class ChartView extends View {
    interface OnPick { void pick(int index); }

    private long[] v = new long[0];
    private String[] lb = new String[0];
    private int sel = -1;
    OnPick cb;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;

    ChartView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
    }

    void set(long[] values, String[] labels, int selected) {
        v = values; lb = labels; sel = selected;
        invalidate();
    }

    private float padX() { return 8 * d; }

    @Override protected void onDraw(Canvas c) {
        int n = v.length;
        if (n == 0) return;
        float w = getWidth(), h = getHeight(), padX = padX(), bottom = 18 * d, top = 6 * d;
        float slot = (w - 2 * padX) / n, bw = slot * 0.7f, ch = h - bottom - top;
        long max = 1;
        for (long x : v) if (x > max) max = x;
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(10 * d);
        for (int i = 0; i < n; i++) {
            float x = padX + slot * i + (slot - bw) / 2;
            float bh = ch * v[i] / (float) max;
            if (v[i] > 0 && bh < 2 * d) bh = 2 * d;
            p.setColor(i == sel ? 0xFFFFB300 : 0xFF4C8DFF);
            c.drawRect(x, top + ch - bh, x + bw, top + ch, p);
            if (lb[i] != null && !lb[i].isEmpty()) {
                p.setColor(0xFF999999);
                c.drawText(lb[i], x + bw / 2, h - 4 * d, p);
            }
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP && cb != null && v.length > 0) {
            float slot = (getWidth() - 2 * padX()) / v.length;
            int i = (int) ((e.getX() - padX()) / slot);
            if (i < 0) i = 0;
            if (i >= v.length) i = v.length - 1;
            cb.pick(i);
            performClick();
        }
        return true;
    }

    @Override public boolean performClick() { return super.performClick(); }
}
