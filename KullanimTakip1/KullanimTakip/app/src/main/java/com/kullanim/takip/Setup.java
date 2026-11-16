package com.kullanim.takip;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * Kurulum sihirbazı: eksik izin/ayarları tek tek açıklar, kullanıcı "Tamam" deyince
 * ilgili ayar sayfasını açar, geri dönünce kaldığı yerden devam eder.
 * (Android, uygulamanın bu izinleri kendi kendine açmasına izin vermez; en fazla
 * doğru sayfayı açabilir. Her adım kullanıcının onayıyla ilerler.)
 */
final class Setup {
    static boolean active;       // sihirbaz sürüyor: ayar ekranından dönünce devam eder
    static boolean dismissed;    // bu oturumda "Şimdi değil" denildi
    private static AlertDialog cur;
    private static final boolean[] tried = new boolean[7];

    private static final int NOTIF = 0, OVERLAY = 1, BATTERY = 2, ACCESS = 3,
            AUTOSTART = 4, POPUP = 5, LOCK = 6, COUNT = 7;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("setup", Context.MODE_PRIVATE); }
    static boolean offered(Context c) { return sp(c).getBoolean("offered", false); }
    static void markOffered(Context c) { sp(c).edit().putBoolean("offered", true).apply(); }

    private static boolean isXiaomi() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco");
    }

    static boolean svcEnabled(Context c) {
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(c.getPackageName() + "/");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean applies(int s) {
        switch (s) {
            case NOTIF: return Build.VERSION.SDK_INT >= 33;
            case OVERLAY:
            case BATTERY: return Build.VERSION.SDK_INT >= 23;
            case ACCESS: return true;
            default: return isXiaomi();            // AUTOSTART, POPUP, LOCK
        }
    }

    private static boolean done(Activity a, int s) {
        if (!applies(s)) return true;
        switch (s) {
            case NOTIF:
                return a.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
            case OVERLAY:
                return Settings.canDrawOverlays(a);
            case BATTERY: {
                PowerManager pm = (PowerManager) a.getSystemService(Context.POWER_SERVICE);
                return pm != null && pm.isIgnoringBatteryOptimizations(a.getPackageName());
            }
            case ACCESS:
                return svcEnabled(a);
            case AUTOSTART: return sp(a).getBoolean("as_done", false);   // doğrulanamaz: onaya göre
            case POPUP: return sp(a).getBoolean("popup_done", false);
            default: return sp(a).getBoolean("lock_done", false);
        }
    }

    static boolean anyMissing(Activity a) {
        for (int i = 0; i < COUNT; i++) if (!done(a, i)) return true;
        return false;
    }

    static void start(Activity a) {
        active = true;
        dismissed = false;
        for (int i = 0; i < COUNT; i++) tried[i] = false;
        next(a);
    }

    static void maybeStart(Activity a) {
        if (!active && !dismissed && anyMissing(a)) start(a);
    }

    /** Sıradaki eksik adımı gösterir; hepsi bitince sihirbazı kapatır. */
    static void next(final Activity a) {
        if (cur != null && cur.isShowing()) return;

        ArrayList<Integer> order = new ArrayList<>();
        for (int i = 0; i < COUNT; i++) if (applies(i)) order.add(i);

        int step = -1, stepNo = 0;
        boolean unfinished = false;
        for (int k = 0; k < order.size(); k++) {
            int i = order.get(k);
            if (done(a, i)) continue;
            unfinished = true;
            if (!tried[i] && step < 0) { step = i; stepNo = k + 1; }
        }

        if (step < 0) {
            if (active) {
                active = false;
                Toast.makeText(a, unfinished
                        ? "Bazı ayarlar tamamlanmadı — Limit sekmesindeki durum satırına bakın"
                        : "Kurulum tamam ✓", Toast.LENGTH_LONG).show();
            }
            return;
        }

        final int s = step;
        String[] t = text(s);
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle("Kurulum " + stepNo + "/" + order.size() + " · " + t[0])
                .setMessage(t[1])
                .setCancelable(false)
                .setPositiveButton(t[2], (d, w) -> { tried[s] = true; act(a, s); })
                .setNegativeButton("Şimdi değil", (d, w) -> { active = false; dismissed = true; });
        if (s == ACCESS && Build.VERSION.SDK_INT >= 33) {
            b.setNeutralButton("Uygulama bilgisi", (d, w) -> a.startActivity(
                    new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName()))));
        }
        cur = b.show();
    }

    private static String[] text(int s) {
        switch (s) {
            case NOTIF:
                return new String[]{"Bildirim izni",
                        "Koruma hizmeti çalışırken küçük bir bildirim gösterir. Bu, telefonun hizmeti kapatmasını zorlaştırır. "
                                + "Bir sonraki ekranda \"İzin ver\"e dokunun.", "Tamam, izin iste"};
            case OVERLAY:
                return new String[]{"Üstte gösterme izni",
                        "Süre dolunca oyunun üstüne \"Süre doldu\" ekranı çizebilmek için gerekir. "
                                + "Açılan listede Kullanım Takip'i bulup anahtarı açın.", "Tamam, ayarı aç"};
            case BATTERY:
                return new String[]{"Pil kısıtlaması",
                        "Telefonun korumayı arka planda kapatmasını önler. Sonraki ekranda \"İzin ver\"e dokunun.",
                        "Tamam, ayarı aç"};
            case ACCESS:
                return new String[]{"Erişilebilirlik hizmeti",
                        "Süre dolunca uygulamayı anında Ana Ekran'a döndürür. Ekran içeriğini okumaz, yalnızca hangi "
                                + "uygulamanın açıldığını görür. Listede \"Kullanım Takip Limit\"i bulup açın.\n\n"
                                + "Seçenek gri ise: Uygulama bilgisi › ⋮ › \"Kısıtlı ayara izin ver\", sonra tekrar deneyin.",
                        "Tamam, ayarı aç"};
            case AUTOSTART:
                return new String[]{"Otomatik başlatma (Xiaomi)",
                        "Telefon uygulamayı kapatırsa korumanın yeniden başlayabilmesi için listeden Kullanım Takip'in "
                                + "Otomatik başlatma anahtarını açın.", "Tamam, ayarı aç"};
            case POPUP:
                return new String[]{"Arka plan pencere izni (Xiaomi)",
                        "Açılan sayfada \"Diğer izinler\" bölümünden \"Arka planda açılan pencereler\" ve "
                                + "\"Ekran kilidinde göster\" izinlerini açın. Süre dolunca Ana Ekran'a dönülebilmesi için gerekir.",
                        "Tamam, ayarı aç"};
            default:
                return new String[]{"Uygulamayı kilitleyin (Xiaomi)",
                        "Son uygulamalar ekranını açın, Kullanım Takip kartını basılı tutup kilit simgesine dokunun.\n\n"
                                + "Kartı yukarı kaydırarak KAPATMAYIN: Xiaomi uygulamayı kapatınca koruma ve erişilebilirlik "
                                + "hizmeti de durur.", "Yaptım"};
        }
    }

    private static void act(final Activity a, int s) {
        String pkg = a.getPackageName();
        try {
            switch (s) {
                case NOTIF:
                    if (!sp(a).getBoolean("notif_asked", false)) {
                        sp(a).edit().putBoolean("notif_asked", true).apply();
                        a.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
                    } else {
                        a.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, pkg));
                    }
                    break;
                case OVERLAY:
                    a.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + pkg)));
                    break;
                case BATTERY:
                    try {
                        a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + pkg)));
                    } catch (Exception e) {
                        a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                    break;
                case ACCESS:
                    a.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    break;
                case AUTOSTART:
                    sp(a).edit().putBoolean("as_done", true).apply();
                    miui(a, "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity", false);
                    break;
                case POPUP:
                    sp(a).edit().putBoolean("popup_done", true).apply();
                    miui(a, "com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity", true);
                    break;
                default:   // LOCK: ayar sayfası yok, onayla ilerle
                    sp(a).edit().putBoolean("lock_done", true).apply();
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override public void run() { next(a); }
                    });
                    break;
            }
        } catch (Exception ignored) { }
    }

    private static void miui(Activity a, String p, String c, boolean perm) {
        try {
            Intent i = new Intent();
            if (perm) {
                i.setAction("miui.intent.action.APP_PERM_EDITOR");
                i.putExtra("extra_pkgname", a.getPackageName());
            }
            i.setClassName(p, c);
            a.startActivity(i);
        } catch (Exception e) {
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + a.getPackageName())));
        }
    }
}
