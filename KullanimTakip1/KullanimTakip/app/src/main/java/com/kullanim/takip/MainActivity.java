package com.kullanim.takip;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends Activity {
    static final int HOUR = 0, DAY = 1, WEEK = 2;
    private static final String[] WD = {"Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz"};
    private static final int BG = 0xFF121212, PANEL = 0xFF1E1E1E, ACCENT = 0xFF1E88E5;

    private int mode = DAY, sel = -1, gen = 0;
    private long anchor = System.currentTimeMillis();
    private Stats.Result last;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<String, String> labels = new ConcurrentHashMap<>();
    private final ArrayList<Row> rows = new ArrayList<>();

    private final TextView[] tabs = new TextView[3];
    private TextView title, total;
    private ChartView chart;
    private ListView list;
    private LinearLayout content, permBox, analizBox;
    private LimitPanel limitPanel;
    private final TextView[] sections = new TextView[2];
    private int sectionIdx = 0;

    private static final class Row {
        final String name; final long ms;
        Row(String n, long m) { name = n; ms = m; }
    }

    // ---------------------------------------------------------------- UI
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        // --- izin kutusu
        permBox = new LinearLayout(this);
        permBox.setOrientation(LinearLayout.VERTICAL);
        permBox.setGravity(Gravity.CENTER);
        permBox.setPadding(dp(24), dp(24), dp(24), dp(24));
        TextView pt = tv("Kullanım verilerini okuyabilmek için \"Kullanım erişimi\" izni gerekir.\n"
                + "Açılan listede Kullanım Takip'i bulup izni açın. Veriler cihazdan çıkmaz.", 16, 0xFFEEEEEE);
        pt.setGravity(Gravity.CENTER);
        Button pb = new Button(this);
        pb.setText("İzin ver");
        pb.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        permBox.addView(pt);
        permBox.addView(pb);

        // --- ana içerik
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout tabRow = new LinearLayout(this);
        String[] names = {"Saatlik", "Günlük", "Haftalık"};
        for (int i = 0; i < 3; i++) {
            final int m = i;
            TextView t = tv(names[i], 15, 0xFFFFFFFF);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(12), 0, dp(12));
            t.setOnClickListener(v -> { mode = m; sel = -1; last = null; paintTabs(); refresh(); });
            tabs[i] = t;
            tabRow.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        TextView prev = tv("‹", 28, 0xFFFFFFFF);
        TextView next = tv("›", 28, 0xFFFFFFFF);
        prev.setGravity(Gravity.CENTER); next.setGravity(Gravity.CENTER);
        prev.setOnClickListener(v -> step(-1));
        next.setOnClickListener(v -> step(1));
        title = tv("", 15, 0xFFEEEEEE);
        title.setGravity(Gravity.CENTER);
        title.setOnClickListener(v -> { anchor = System.currentTimeMillis(); sel = -1; last = null; refresh(); });
        nav.addView(prev, new LinearLayout.LayoutParams(dp(56), dp(44)));
        nav.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
        nav.addView(next, new LinearLayout.LayoutParams(dp(56), dp(44)));

        total = tv("", 14, 0xFFB0BEC5);
        total.setPadding(dp(12), dp(4), dp(12), dp(4));

        chart = new ChartView(this);
        chart.cb = i -> {
            if (last == null) return;
            if (mode == HOUR) sel = i;
            else if (mode == WEEK) sel = (sel == i) ? -1 : i;
            else return;
            render();
        };

        list = new ListView(this);
        list.setDivider(null);
        list.setAdapter(adapter);

        analizBox = new LinearLayout(this);
        analizBox.setOrientation(LinearLayout.VERTICAL);
        analizBox.addView(tabRow);
        analizBox.addView(nav);
        analizBox.addView(total);
        analizBox.addView(chart, new LinearLayout.LayoutParams(-1, dp(130)));
        analizBox.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        limitPanel = new LimitPanel(this);

        LinearLayout secRow = new LinearLayout(this);
        String[] sn = {"Analiz", "Limit"};
        for (int i = 0; i < 2; i++) {
            final int si = i;
            TextView t = tv(sn[i], 16, 0xFFFFFFFF);
            t.getPaint().setFakeBoldText(true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(12), 0, dp(12));
            t.setOnClickListener(v -> showSection(si));
            sections[i] = t;
            secRow.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        content.addView(secRow);
        content.addView(analizBox, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(limitPanel, new LinearLayout.LayoutParams(-1, 0, 1));

        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        root.addView(permBox, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        paintTabs();
        showSection(0);
    }

    @Override protected void onResume() {
        super.onResume();
        boolean ok = hasAccess();
        content.setVisibility(ok ? View.VISIBLE : View.GONE);
        permBox.setVisibility(ok ? View.GONE : View.VISIBLE);
        if (ok) refresh();
        if (ok) GuardService.start(this);
        if (ok) setupResume();
        limitPanel.onResume();
    }

    @Override protected void onStop() {
        super.onStop();
        limitPanel.lock();                 // PIN her seferinde tekrar istenir
        if (sectionIdx == 1) showSection(0);
    }

    /** İlk açılışta sihirbazı bir kez önerir; ayar ekranından dönünce kaldığı yerden sürdürür. */
    private void setupResume() {
        if (Setup.active) {
            Setup.next(this);
        } else if (!Setup.offered(this)) {
            Setup.markOffered(this);
            Setup.start(this);
        }
    }

    private boolean askedNotif;

    private void askNotif() {
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            askedNotif = true;
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
        }
    }

    private void showSection(int s) {
        sectionIdx = s;
        analizBox.setVisibility(s == 0 ? View.VISIBLE : View.GONE);
        limitPanel.setVisibility(s == 1 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < 2; i++) sections[i].setBackgroundColor(i == s ? ACCENT : 0xFF000000);
        if (s == 1) limitPanel.open(() -> showSection(0));
    }

    private void paintTabs() {
        for (int i = 0; i < 3; i++) tabs[i].setBackgroundColor(i == mode ? 0xFF3A4A52 : PANEL);
    }

    // ----------------------------------------------------------- veri akışı
    private void step(int dir) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(anchor);
        c.add(Calendar.DAY_OF_YEAR, dir * (mode == WEEK ? 7 : 1));
        if (dir > 0 && bounds(mode, c.getTimeInMillis())[0] > System.currentTimeMillis()) return;
        anchor = c.getTimeInMillis();
        sel = -1; last = null;
        refresh();
    }

    private void refresh() {
        final int g = ++gen;
        final long[] b = bounds(mode, anchor);
        new Thread(() -> {
            Stats.Result r = Stats.compute(getApplicationContext(), b);
            for (String p : r.perApp.keySet()) label(p);
            ui.post(() -> { if (g == gen) { last = r; render(); } });
        }).start();
    }

    private void render() {
        Stats.Result r = last;
        if (r == null) return;
        long[] b = r.bounds;
        int nb = b.length - 1;

        long[] bt = new long[nb];
        for (long[] a : r.perApp.values()) for (int i = 0; i < nb; i++) bt[i] += a[i];

        if (mode == HOUR && (sel < 0 || sel >= nb)) {   // en yoğun saati otomatik seç
            long best = -1;
            for (int i = 0; i < nb; i++) if (bt[i] > best) { best = bt[i]; sel = i; }
        }
        int s = mode == DAY ? -1 : sel;

        rows.clear();
        long sum = 0;
        for (Map.Entry<String, long[]> e : r.perApp.entrySet()) {
            long ms = 0;
            if (s >= 0) ms = e.getValue()[s];
            else for (long x : e.getValue()) ms += x;
            if (ms < 1000) continue;
            rows.add(new Row(label(e.getKey()), ms));
            sum += ms;
        }
        Collections.sort(rows, (x, y) -> Long.compare(y.ms, x.ms));
        adapter.notifyDataSetChanged();

        // grafik etiketleri
        String[] lb = new String[nb];
        Calendar c = Calendar.getInstance();
        for (int i = 0; i < nb; i++) {
            if (mode == WEEK) {
                c.setTimeInMillis(b[i]);
                lb[i] = WD[(c.get(Calendar.DAY_OF_WEEK) + 5) % 7];
            } else {
                c.setTimeInMillis(b[i]);
                int h = c.get(Calendar.HOUR_OF_DAY);
                lb[i] = (h % 6 == 0) ? String.valueOf(h) : "";
            }
        }
        chart.set(bt, lb, s);

        Locale tr = new Locale("tr");
        if (mode == WEEK) {
            title.setText(new SimpleDateFormat("d MMM", tr).format(b[0]) + " – "
                    + new SimpleDateFormat("d MMM yyyy", tr).format(b[nb] - 1));
        } else {
            title.setText(new SimpleDateFormat("d MMMM yyyy, EEEE", tr).format(b[0]));
        }
        String what = "";
        if (s >= 0) {
            if (mode == HOUR) {
                SimpleDateFormat hf = new SimpleDateFormat("HH:mm", tr);
                what = "  ·  " + hf.format(b[s]) + "–" + hf.format(b[s + 1]);
            } else {
                what = "  ·  " + new SimpleDateFormat("d MMM EEEE", tr).format(b[s]);
            }
        }
        total.setText("Toplam: " + fmt(sum) + what + (rows.isEmpty() ? "  (veri yok)" : ""));
    }

    private String label(String p) {
        String l = labels.get(p);
        if (l == null) {
            try {
                PackageManager pm = getPackageManager();
                l = pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString();
            } catch (Exception e) {
                l = p;
            }
            labels.put(p, l);
        }
        return l;
    }

    /** Saatlik/Günlük: o günün saat dilimleri (DST'ye duyarlı). Haftalık: Pzt–Paz günleri. */
    static long[] bounds(int mode, long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        if (mode == WEEK) {
            int dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;   // Pzt=0
            c.add(Calendar.DAY_OF_YEAR, -dow);
            long[] b = new long[8];
            b[0] = c.getTimeInMillis();
            for (int i = 1; i <= 7; i++) { c.add(Calendar.DAY_OF_YEAR, 1); b[i] = c.getTimeInMillis(); }
            return b;
        }
        long start = c.getTimeInMillis();
        c.add(Calendar.DAY_OF_YEAR, 1);
        long end = c.getTimeInMillis();
        int n = (int) Math.round((end - start) / 3600000.0);   // 23/24/25
        long[] b = new long[n + 1];
        for (int i = 0; i < n; i++) b[i] = start + i * 3600000L;
        b[n] = end;
        return b;
    }

    static String fmt(long ms) {
        long s = Math.round(ms / 1000.0);
        if (s < 60) return s + " sn";
        long m = s / 60;
        if (m < 60) return m + " dk";
        return (m / 60) + " sa " + (m % 60) + " dk";
    }

    @SuppressWarnings("deprecation")
    private boolean hasAccess() {
        AppOpsManager a = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int m = Build.VERSION.SDK_INT >= 29
                ? a.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
                : a.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
        return m == AppOpsManager.MODE_ALLOWED;
    }

    // ------------------------------------------------------------ yardımcılar
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView tv(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        return t;
    }

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View v, ViewGroup g) {
            RowView r;
            if (v instanceof RowView) r = (RowView) v;
            else {
                r = new RowView(MainActivity.this);
                r.setLayoutParams(new AbsListView.LayoutParams(-1, dp(48)));
            }
            Row w = rows.get(i);
            long max = rows.get(0).ms;
            r.name = w.name; r.time = fmt(w.ms);
            r.frac = max > 0 ? (float) w.ms / max : 0;
            r.invalidate();
            return r;
        }
    };

    /** Liste satırı: tek View, tek Paint -> minimum bellek. */
    private static final class RowView extends View {
        String name = "", time = "";
        float frac;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float d;
        RowView(Context c) { super(c); d = c.getResources().getDisplayMetrics().density; }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), pad = 12 * d;
            p.setTextSize(15 * d);
            p.setColor(0xFFEEEEEE);
            float tw = p.measureText(time);
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText(time, w - pad, 22 * d, p);
            p.setTextAlign(Paint.Align.LEFT);
            int cnt = p.breakText(name, true, w - 2 * pad - tw - 8 * d, null);
            c.drawText(name, 0, cnt, pad, 22 * d, p);
            p.setColor(0xFF2A2A2A);
            c.drawRoundRect(pad, 32 * d, w - pad, 38 * d, 3 * d, 3 * d, p);
            p.setColor(0xFF4C8DFF);
            c.drawRoundRect(pad, 32 * d, pad + (w - 2 * pad) * frac, 38 * d, 3 * d, 3 * d, p);
        }
    }
}
