package com.kullanim.takip;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * Süreleri UsageStatsManager'ın hazır (yaklaşık) toplamlarından DEĞİL,
 * ham olay akışından (activity resume/pause, ekran kapanma, kapanış) hesaplar.
 * Böylece günlük/saatlik/haftalık sınırlarda kesin bölme yapılır.
 */
final class Stats {

    static final class Result {
        final long[] bounds;                       // bucket sınırları (n+1)
        final HashMap<String, long[]> perApp = new HashMap<>(); // paket -> bucket başına ms
        String fgPkg;                              // şu an ön plandaki paket (yoksa null)
        Result(long[] b) { bounds = b; }
    }

    private static final int RESUMED = 1, PAUSED = 2, SCREEN_OFF = 16, KEYGUARD_SHOWN = 17,
            STOPPED = 23, DESTROYED = 24, SHUTDOWN = 26;
    // Aralık başında açık kalmış oturumları yakalamak için geriye bakış
    private static final long LOOKBACK = 12L * 3600 * 1000;

    static Result compute(Context ctx, long[] bounds) {
        Result r = new Result(bounds);
        long now = System.currentTimeMillis();
        if (bounds[0] >= now) return r;

        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        UsageEvents events = usm == null ? null : usm.queryEvents(bounds[0] - LOOKBACK, now);
        if (events == null) return r;

        HashMap<String, HashSet<String>> open = new HashMap<>();
        HashMap<String, Long> start = new HashMap<>();
        UsageEvents.Event e = new UsageEvents.Event();
        String[] fg = new String[1];

        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            int type = e.getEventType();
            long t = e.getTimeStamp();
            switch (type) {
                case RESUMED: {
                    String p = e.getPackageName();
                    if (p == null) break;
                    String c = e.getClassName() == null ? "" : e.getClassName();
                    HashSet<String> s = open.get(p);
                    if (s == null) { s = new HashSet<>(); open.put(p, s); }
                    if (s.isEmpty()) start.put(p, t);
                    s.add(c);
                    fg[0] = p;
                    break;
                }
                case PAUSED:
                case STOPPED:
                case DESTROYED: {
                    String p = e.getPackageName();
                    if (p == null) break;
                    String c = e.getClassName() == null ? "" : e.getClassName();
                    HashSet<String> s = open.get(p);
                    if (s != null && s.remove(c) && s.isEmpty()) {
                        add(r, p, start.get(p), t);
                        if (p.equals(fg[0])) fg[0] = null;
                    }
                    break;
                }
                case SCREEN_OFF:
                case KEYGUARD_SHOWN:
                case SHUTDOWN:
                    closeAll(r, open, start, t);
                    fg[0] = null;
                    break;
                default:
                    break;
            }
        }
        // Hâlâ açık olanlar şu ana kadar sayılır
        HashSet<String> fs = fg[0] == null ? null : open.get(fg[0]);
        if (fs != null && !fs.isEmpty()) r.fgPkg = fg[0];
        closeAll(r, open, start, now);
        return r;
    }

    private static void closeAll(Result r, HashMap<String, HashSet<String>> open,
                                 HashMap<String, Long> start, long t) {
        for (Map.Entry<String, HashSet<String>> en : open.entrySet()) {
            HashSet<String> s = en.getValue();
            if (!s.isEmpty()) {
                add(r, en.getKey(), start.get(en.getKey()), t);
                s.clear();
            }
        }
    }

    /** [s,e) oturumunu bucket sınırlarına göre bölerek ekler. */
    private static void add(Result r, String pkg, long s, long e) {
        long[] b = r.bounds;
        int nb = b.length - 1;
        if (e <= b[0] || s >= b[nb] || e <= s) return;
        long[] arr = r.perApp.get(pkg);
        if (arr == null) { arr = new long[nb]; r.perApp.put(pkg, arr); }
        for (int i = 0; i < nb; i++) {
            long lo = Math.max(s, b[i]), hi = Math.min(e, b[i + 1]);
            if (hi > lo) arr[i] += hi - lo;
        }
    }
}
