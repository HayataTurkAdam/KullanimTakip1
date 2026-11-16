package com.kullanim.takip;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.util.HashMap;

/**
 * Olay tabanlı çalışma korunur (pencere değişince kontrol + tek hassas sayaç),
 * üzerine üç güvence eklendi:
 *  1) Erişilebilirlik olayı UsageStats kaydından önce gelebilir -> olay paketi "ipucu" olarak
 *     alınır, ön plan doğrulanana kadar 1 sn arayla (en çok 5 kez) yeniden denenir.
 *  2) Ekran açıkken her 15 sn'de bir güvenlik kontrolü (olay kaçsa, uygulama zaten açıksa,
 *     limit sonradan eklendiyse bile en geç ~15 sn içinde yakalar). Ekran kapalıyken çalışmaz.
 *  3) Hizmet bağlanınca ve limit kaydedilince hemen kontrol edilir.
 * Ekran içeriğini okumaz, sistemde hiçbir şeyi değiştirmez.
 */
public class LimitService extends AccessibilityService {
    private static final long WARN = 5 * 60_000L;   // bitmesine 5 dk kala uyarı
    private static final long BEAT = 15_000L;       // güvenlik kontrolü aralığı

    /** Panel tanılaması için (aynı süreçte okunur). */
    static volatile long lastBeat;
    static volatile String lastFg;
    static volatile String lastErr;
    private static volatile LimitService inst;

    private HandlerThread ht;
    private volatile Handler h;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String warnedPkg;
    private volatile int token;
    private boolean receiverOn;

    private final Runnable checkRun = new Runnable() {
        @Override public void run() { check(null, 0); }
    };

    private final Runnable beatRun = new Runnable() {
        @Override public void run() {
            Handler hh = h;
            if (hh == null) return;
            check(null, 0);
            hh.postDelayed(this, BEAT);
        }
    };

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Handler hh = h;
            if (hh == null) return;
            hh.removeCallbacks(beatRun);
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) hh.post(beatRun);
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        ht = new HandlerThread("limit-check");
        ht.start();
        h = new Handler(ht.getLooper());
        inst = this;
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screen, f);
        receiverOn = true;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null || pm.isInteractive()) h.post(beatRun);   // hemen ilk kontrol
    }

    /** Limit kaydedilince panelden çağrılır. */
    static void poke() {
        final LimitService s = inst;
        final Handler hh = s == null ? null : s.h;
        if (hh != null) hh.post(new Runnable() { @Override public void run() { s.check(null, 0); } });
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        Handler hh = h;
        if (hh == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = e.getPackageName();
        if (p == null) return;
        final String pkg = p.toString();
        if (!Limits.has(this, pkg)) return;
        token++;
        hh.post(new Runnable() { @Override public void run() { check(pkg, 0); } });
    }

    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(Intent i) {
        inst = null;
        if (receiverOn) {
            try { unregisterReceiver(screen); } catch (Exception ignored) { }
            receiverOn = false;
        }
        if (h != null) h.removeCallbacksAndMessages(null);
        if (ht != null) ht.quitSafely();
        h = null; ht = null;
        return super.onUnbind(i);
    }

    /** Arka plan thread'inde çalışır. hint: pencere olayını gönderen limitli paket (varsa). */
    private void check(final String hint, final int tries) {
        try { checkInner(hint, tries); } catch (Throwable t) { lastErr = String.valueOf(t); }
    }

    private void checkInner(final String hint, final int tries) {
        final Handler hh = h;
        if (hh == null) return;
        lastBeat = SystemClock.elapsedRealtime();

        HashMap<String, Integer> lim = Limits.all(this);
        if (lim.isEmpty()) { lastFg = null; return; }

        Stats.Result r = Stats.compute(this, MainActivity.bounds(MainActivity.DAY, System.currentTimeMillis()));
        String fg = r.fgPkg;
        lastFg = fg;

        // Olay, kullanım kaydından önce gelmiş olabilir: ön plan doğrulanana kadar tekrar dene
        if (hint != null && lim.containsKey(hint) && !hint.equals(fg) && tries < 5) {
            final int t = token;
            hh.postDelayed(new Runnable() {
                @Override public void run() { if (t == token) check(hint, tries + 1); }
            }, 1000);
        }

        if (fg == null) return;
        Integer lm = lim.get(fg);
        if (lm == null) return;

        long used = 0;
        long[] a = r.perApp.get(fg);
        if (a != null) for (long x : a) used += x;
        long limitMs = lm * 60_000L;
        long remain = limitMs - used;

        if (remain <= 0) {
            warnedPkg = null;
            block(fg, lm);
            hh.removeCallbacks(checkRun);
            hh.postDelayed(checkRun, 2500);        // hâlâ ön plandaysa tekrar engelle
            return;
        }

        long next = remain;
        if (limitMs >= 2 * WARN) {
            if (remain > WARN) {
                warnedPkg = null;
                next = remain - WARN;
            } else if (!fg.equals(warnedPkg)) {
                warnedPkg = fg;
                final String msg = Limits.label(this, fg) + ": " + Math.max(1, Math.round(remain / 60000.0)) + " dk kaldı";
                ui.post(new Runnable() {
                    @Override public void run() { Toast.makeText(LimitService.this, msg, Toast.LENGTH_LONG).show(); }
                });
            }
        }
        hh.removeCallbacks(checkRun);
        hh.postDelayed(checkRun, Math.max(next, 1000) + 200);
    }

    private void block(String pkg, int limMin) {
        final String name = Limits.label(this, pkg);
        final int min = limMin;
        ui.post(new Runnable() {
            @Override public void run() {
                performGlobalAction(GLOBAL_ACTION_HOME);
                ui.postDelayed(new Runnable() {
                    @Override public void run() {
                        try {
                            Intent i = new Intent(LimitService.this, BlockActivity.class)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    .putExtra("name", name).putExtra("min", min);
                            startActivity(i);
                        } catch (Exception ex) {
                            Toast.makeText(LimitService.this, name + ": günlük süre doldu", Toast.LENGTH_LONG).show();
                        }
                    }
                }, 300);
            }
        });
    }
}
