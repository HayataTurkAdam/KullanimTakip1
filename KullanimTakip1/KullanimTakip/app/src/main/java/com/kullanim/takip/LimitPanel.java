package com.kullanim.takip;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** "Limit" bölümü: limit listesi, ekleme/düzenleme, PIN kilidi, servis durumu. */
final class LimitPanel extends LinearLayout {
    private static final class Item { String pkg, name; int min; long used; }

    private final Activity act;
    private final LinearLayout svcBox;
    private final TextView emptyTv, statusTv;
    private final Button battBtn, ovlBtn, miBtn;
    private boolean ticking;
    private final ListView list;
    private final ArrayList<Item> items = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean unlocked;
    private int gen;

    LimitPanel(Activity a) {
        super(a);
        act = a;
        setOrientation(VERTICAL);
        int p = dp(12);

        // --- servis kapalıysa uyarı kutusu
        svcBox = new LinearLayout(a);
        svcBox.setOrientation(VERTICAL);
        svcBox.setPadding(p, p, p, p);
        svcBox.setBackgroundColor(0xFF3E2723);
        TextView st = tv("Limitlerin çalışması için \"Kullanım Takip Limit\" erişilebilirlik hizmeti açık olmalı. "
                + "Ekran içeriğini okumaz; yalnızca hangi uygulamanın açıldığını görür.", 13, 0xFFFFE0B2);
        svcBox.addView(st);
        svcBox.addView(btn("Erişilebilirlik ayarlarını aç",
                v -> act.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        if (Build.VERSION.SDK_INT >= 33) {
            svcBox.addView(tv("Seçenek gri/kapalıysa: Uygulama bilgisi › ⋮ › \"Kısıtlı ayara izin ver\", sonra tekrar deneyin.",
                    12, 0xFFFFCC80));
            svcBox.addView(btn("Uygulama bilgisini aç", v -> act.startActivity(
                    new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + act.getPackageName())))));
        }
        addView(svcBox);

        statusTv = tv("", 12, 0xFF90A4AE);
        statusTv.setPadding(p, dp(6), p, dp(2));
        addView(statusTv);
        battBtn = btn("Pil optimizasyonu ayarlarını aç", v -> {
            try {
                act.startActivity(new Intent("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"));
            } catch (Exception e) {
                act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + act.getPackageName())));
            }
        });
        battBtn.setVisibility(GONE);
        addView(battBtn);
        ovlBtn = btn("\"Üstte göster\" iznini ver (yedek koruma)", v -> act.startActivity(
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + act.getPackageName()))));
        ovlBtn.setVisibility(GONE);
        addView(ovlBtn);
        miBtn = btn("Xiaomi: Otomatik başlatma ayarını aç", v -> {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"));
                act.startActivity(i);
            } catch (Exception e) {
                act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + act.getPackageName())));
            }
        });
        miBtn.setVisibility(GONE);
        addView(miBtn);

        // --- başlık satırı
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(p, dp(4), p, dp(4));
        TextView ht = tv("Günlük limitler", 16, 0xFFFFFFFF);
        head.addView(ht, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(btn("Kurulum", v -> Setup.start(act)));
        head.addView(btn("+ Ekle", v -> pickApp()));
        addView(head);

        emptyTv = tv("Henüz limit yok.\n\"+ Ekle\" ile bir uygulama seçip günlük süre belirleyin.", 14, 0xFF90A4AE);
        emptyTv.setPadding(p, p, p, p);
        addView(emptyTv);

        list = new ListView(a);
        list.setDivider(null);
        list.setAdapter(adapter);
        list.setOnItemClickListener((pa, v, i, id) -> {
            Item it = items.get(i);
            askMinutes(it.pkg, it.name, it.min);
        });
        addView(list, new LayoutParams(-1, 0, 1));
    }

    // ------------------------------------------------------------ yaşam döngüsü
    void open(final Runnable deny) {
        startTick();
        if (unlocked) { refresh(); Setup.maybeStart(act); return; }
        if (Limits.hasPin(act)) askPin(deny); else askNewPin(deny);
    }

    void lock() {
        unlocked = false;
        items.clear();
        adapter.notifyDataSetChanged();
        emptyTv.setVisibility(GONE);
    }

    void onResume() {
        updateStatus();
        startTick();
        if (unlocked) refresh();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateStatus();
            if (isShown()) ui.postDelayed(this, 3000); else ticking = false;
        }
    };

    private void startTick() {
        if (!ticking) { ticking = true; ui.post(tick); }
    }

    /** İki korumanın da gerçekten çalışıp çalışmadığını gösterir (açık görünmesi yetmez). */
    private void updateStatus() {
        boolean en = svcEnabled();
        svcBox.setVisibility(en ? GONE : VISIBLE);
        long now = SystemClock.elapsedRealtime();
        boolean aAlive = en && LimitService.lastBeat > 0 && now - LimitService.lastBeat < 45_000;
        boolean gAlive = GuardService.running && GuardService.lastBeat > 0 && now - GuardService.lastBeat < 30_000;
        boolean ovl = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(act);
        boolean gOk = gAlive && ovl;

        StringBuilder sb = new StringBuilder();
        sb.append("Erişilebilirlik hizmeti: ")
          .append(aAlive ? "çalışıyor" : (en ? "açık görünüyor ama ÇALIŞMIYOR" : "KAPALI"));
        sb.append("\nYedek koruma: ").append(gAlive ? "çalışıyor" : "ÇALIŞMIYOR");
        if (!ovl) sb.append(" · \"Üstte göster\" izni YOK");
        if (en && !aAlive) {
            sb.append("\nİpucu: hizmeti kapatıp yeniden açın (güncellemeden sonra gerekebilir).");
        }
        String fg = aAlive ? LimitService.lastFg : null;
        if (fg != null) sb.append("\nÖn plan: ").append(Limits.label(act, fg));
        String er = LimitService.lastErr != null ? LimitService.lastErr : GuardService.lastErr;
        if (er != null) sb.append("\nSon hata: ").append(er);

        statusTv.setText(sb.toString());
        statusTv.setTextColor(aAlive && gOk ? 0xFF66BB6A : (aAlive || gOk ? 0xFFFFB300 : 0xFFEF5350));

        boolean allGood = aAlive && gAlive;
        battBtn.setVisibility(allGood ? GONE : VISIBLE);
        ovlBtn.setVisibility(ovl ? GONE : VISIBLE);
        String man = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
        boolean xiaomi = man.contains("xiaomi") || man.contains("redmi") || man.contains("poco");
        miBtn.setVisibility(xiaomi && !allGood ? VISIBLE : GONE);
    }

    private boolean svcEnabled() {
        try {
            String s = Settings.Secure.getString(act.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(act.getPackageName() + "/");
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------ veri
    void refresh() {
        final int g = ++gen;
        new Thread(() -> {
            Context c = act.getApplicationContext();
            HashMap<String, Integer> lim = Limits.all(c);
            Stats.Result r = Stats.compute(c, MainActivity.bounds(MainActivity.DAY, System.currentTimeMillis()));
            ArrayList<Item> out = new ArrayList<>();
            for (Map.Entry<String, Integer> e : lim.entrySet()) {
                Item it = new Item();
                it.pkg = e.getKey();
                it.min = e.getValue();
                it.name = Limits.label(c, it.pkg);
                long[] a = r.perApp.get(it.pkg);
                if (a != null) for (long x : a) it.used += x;
                out.add(it);
            }
            Collections.sort(out, (x, y) -> x.name.compareToIgnoreCase(y.name));
            ui.post(() -> {
                if (g != gen) return;
                items.clear();
                items.addAll(out);
                adapter.notifyDataSetChanged();
                emptyTv.setVisibility(items.isEmpty() ? VISIBLE : GONE);
            });
        }).start();
    }

    // ------------------------------------------------------------ diyaloglar
    private void pickApp() {
        PackageManager pm = act.getPackageManager();
        List<ResolveInfo> l = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        final ArrayList<String[]> apps = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (ResolveInfo ri : l) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(act.getPackageName()) || !seen.add(pkg)) continue;
            apps.add(new String[]{ri.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (x, y) -> x[0].compareToIgnoreCase(y[0]));
        String[] names = new String[apps.size()];
        for (int i = 0; i < names.length; i++) names[i] = apps.get(i)[0];
        new AlertDialog.Builder(act).setTitle("Uygulama seç").setItems(names, (d, i) -> {
            Integer cur = Limits.all(act).get(apps.get(i)[1]);
            askMinutes(apps.get(i)[1], apps.get(i)[0], cur == null ? 60 : cur);
        }).show();
    }

    private void askMinutes(final String pkg, final String name, int cur) {
        final EditText et = new EditText(act);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(cur));
        et.setSelectAllOnFocus(true);
        AlertDialog.Builder b = new AlertDialog.Builder(act)
                .setTitle(name + " · günlük limit (dk)")
                .setView(et)
                .setPositiveButton("Kaydet", (d, w) -> {
                    int m;
                    try { m = Integer.parseInt(et.getText().toString().trim()); } catch (Exception e) { m = 0; }
                    if (m < 1 || m > 1440) {
                        Toast.makeText(act, "1–1440 arası dakika girin", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Limits.set(act, pkg, m);
                    refresh();
                })
                .setNegativeButton("İptal", null);
        if (Limits.has(act, pkg)) {
            b.setNeutralButton("Limiti sil", (d, w) -> { Limits.remove(act, pkg); refresh(); });
        }
        b.show();
    }

    private EditText pinField() {
        EditText et = new EditText(act);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        return et;
    }

    private void askPin(final Runnable deny) {
        final EditText et = pinField();
        new AlertDialog.Builder(act).setTitle("Ebeveyn PIN'i").setView(et).setCancelable(false)
                .setPositiveButton("Aç", (d, w) -> {
                    if (Limits.checkPin(act, et.getText().toString())) {
                        unlocked = true;
                        refresh();
                        Setup.maybeStart(act);
                    } else {
                        Toast.makeText(act, "Yanlış PIN", Toast.LENGTH_SHORT).show();
                        deny.run();
                    }
                })
                .setNegativeButton("İptal", (d, w) -> deny.run())
                .show();
    }

    private void askNewPin(final Runnable deny) {
        final EditText et = pinField();
        et.setHint("En az 4 rakam");
        new AlertDialog.Builder(act).setTitle("Ebeveyn PIN'i belirleyin")
                .setMessage("Limitleri görmek ve değiştirmek için gerekir.")
                .setView(et).setCancelable(false)
                .setPositiveButton("Kaydet", (d, w) -> {
                    String s = et.getText().toString();
                    if (s.length() < 4) {
                        Toast.makeText(act, "PIN en az 4 haneli olmalı", Toast.LENGTH_SHORT).show();
                        askNewPin(deny);
                        return;
                    }
                    Limits.setPin(act, s);
                    unlocked = true;
                    refresh();
                    Setup.maybeStart(act);
                })
                .setNegativeButton("İptal", (d, w) -> deny.run())
                .show();
    }

    // ------------------------------------------------------------ yardımcılar
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView tv(String s, int sp, int color) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button btn(String s, OnClickListener l) {
        Button b = new Button(act);
        b.setAllCaps(false);
        b.setText(s);
        b.setOnClickListener(l);
        return b;
    }

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View v, ViewGroup g) {
            LimitRow r;
            if (v instanceof LimitRow) r = (LimitRow) v;
            else {
                r = new LimitRow(act);
                r.setLayoutParams(new AbsListView.LayoutParams(-1, dp(56)));
            }
            Item it = items.get(i);
            long limitMs = it.min * 60_000L;
            r.name = it.name;
            r.right = MainActivity.fmt(it.used) + " / " + it.min + " dk";
            r.frac = Math.min(1f, (float) it.used / limitMs);
            r.color = it.used >= limitMs ? 0xFFE53935 : (it.used >= 0.8 * limitMs ? 0xFFFFB300 : 0xFF4C8DFF);
            r.invalidate();
            return r;
        }
    };

    private static final class LimitRow extends View {
        String name = "", right = "";
        float frac;
        int color;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float d;

        LimitRow(Context c) { super(c); d = c.getResources().getDisplayMetrics().density; }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), pad = 12 * d;
            p.setTextSize(15 * d);
            p.setColor(0xFFEEEEEE);
            float tw = p.measureText(right);
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText(right, w - pad, 24 * d, p);
            p.setTextAlign(Paint.Align.LEFT);
            int cnt = p.breakText(name, true, w - 2 * pad - tw - 8 * d, null);
            c.drawText(name, 0, cnt, pad, 24 * d, p);
            p.setColor(0xFF2A2A2A);
            c.drawRoundRect(pad, 36 * d, w - pad, 42 * d, 3 * d, 3 * d, p);
            p.setColor(color);
            c.drawRoundRect(pad, 36 * d, pad + (w - 2 * pad) * frac, 42 * d, 3 * d, 3 * d, p);
        }
    }
}
