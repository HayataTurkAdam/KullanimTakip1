package com.kullanim.takip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;

/**
 * YEDEK KORUMA: Erişilebilirlik hizmeti çalışmasa bile limitleri uygular.
 * Ön plan hizmeti (kalıcı bildirimli, telefon tarafından kolay öldürülmez) ekran açıkken
 * her 4 sn'de bir ön plandaki uygulamayı ve bugünkü süresini kontrol eder.
 * Limit dolmuşsa tam ekran "Süre doldu" katmanı çizer ve Ana Ekran'a dönmeyi dener.
 * Ekran kapalıyken hiçbir iş yapmaz. Sistemde hiçbir şeyi değiştirmez.
 */
public class GuardService extends Service {
    static volatile long lastBeat;
    static volatile boolean running;
    static volatile String lastErr;

    private static final long POLL = 4000L;
    private static final int NOTIF_ID = 42;

    private HandlerThread ht;
    private volatile Handler h;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile View overlay;
    private String overlayPkg;
    private boolean receiverOn;

    static void start(Context c) {
        try {
            if (Limits.all(c).isEmpty()) return;
            Intent i = new Intent(c, GuardService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) { }
    }

    static void stop(Context c) {
        try { c.stopService(new Intent(c, GuardService.class)); } catch (Exception ignored) { }
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            Handler hh = h;
            if (hh == null) return;
            try { check(); } catch (Throwable t) { lastErr = String.valueOf(t); }
            hh.postDelayed(this, POLL);
        }
    };

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Handler hh = h;
            if (hh == null) return;
            hh.removeCallbacks(poll);
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) hh.post(poll);
            else hideOverlay();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        startFg();
        ht = new HandlerThread("guard");
        ht.start();
        h = new Handler(ht.getLooper());
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screen, f);
        receiverOn = true;
        running = true;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null || pm.isInteractive()) h.post(poll);
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) { return START_STICKY; }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        running = false;
        if (receiverOn) {
            try { unregisterReceiver(screen); } catch (Exception ignored) { }
            receiverOn = false;
        }
        if (h != null) h.removeCallbacksAndMessages(null);
        if (ht != null) ht.quitSafely();
        h = null; ht = null;
        removeOverlayNow();
        super.onDestroy();
    }

    private void startFg() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("guard", "Limit koruması",
                    NotificationManager.IMPORTANCE_MIN));
            b = new Notification.Builder(this, "guard");
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Kullanım Takip")
                .setContentText("Limit koruması çalışıyor")
                .setOngoing(true);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIF_ID, n);
    }

    /** Arka plan thread'inde çalışır. */
    private void check() {
        lastBeat = SystemClock.elapsedRealtime();
        HashMap<String, Integer> lim = Limits.all(this);
        if (lim.isEmpty()) { hideOverlay(); return; }

        Stats.Result r = Stats.compute(this, MainActivity.bounds(MainActivity.DAY, System.currentTimeMillis()));
        String fg = r.fgPkg;
        Integer lm = fg == null ? null : lim.get(fg);
        if (lm != null) {
            long used = 0;
            long[] a = r.perApp.get(fg);
            if (a != null) for (long x : a) used += x;
            if (used >= lm * 60_000L) { showOverlay(fg, lm); return; }
        }
        hideOverlay();
    }

    // ------------------------------------------------------------------ katman
    private void showOverlay(final String pkg, final int min) {
        final String name = Limits.label(this, pkg);
        ui.post(new Runnable() {
            @Override public void run() {
                if (overlay != null && pkg.equals(overlayPkg)) return;
                removeOverlayNow();
                goHome();                                       // önce Ana Ekran'a dönmeyi dene
                if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(GuardService.this)) return;
                try {
                    float d = getResources().getDisplayMetrics().density;
                    LinearLayout l = new LinearLayout(GuardService.this);
                    l.setOrientation(LinearLayout.VERTICAL);
                    l.setGravity(Gravity.CENTER);
                    l.setBackgroundColor(0xFF121212);
                    int p = Math.round(24 * d);
                    l.setPadding(p, p, p, p);
                    TextView t = new TextView(GuardService.this);
                    t.setTextColor(0xFFFFFFFF);
                    t.setTextSize(20);
                    t.setGravity(Gravity.CENTER);
                    t.setText("⏰\n\nSüre doldu\n\n" + name + " için bugünkü " + min
                            + " dk limit doldu.\nLimit yarın sıfırlanır.");
                    Button ok = new Button(GuardService.this);
                    ok.setText("Ana ekrana dön");
                    ok.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { goHome(); }
                    });
                    l.addView(t);
                    l.addView(ok);

                    int type = Build.VERSION.SDK_INT >= 26
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE;
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                            type,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                            PixelFormat.OPAQUE);
                    lp.gravity = Gravity.TOP | Gravity.START;
                    ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).addView(l, lp);
                    overlay = l;
                    overlayPkg = pkg;
                } catch (Exception e) {
                    lastErr = String.valueOf(e);
                }
            }
        });
    }

    private void hideOverlay() {
        if (overlay == null) return;
        ui.post(new Runnable() { @Override public void run() { removeOverlayNow(); } });
    }

    private void removeOverlayNow() {
        View o = overlay;
        if (o != null) {
            try { ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).removeView(o); } catch (Exception ignored) { }
            overlay = null;
            overlayPkg = null;
        }
    }

    private void goHome() {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
    }
}
