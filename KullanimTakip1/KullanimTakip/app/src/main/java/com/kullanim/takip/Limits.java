package com.kullanim.takip;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Limitler ve ebeveyn PIN'i. Sadece cihazdaki SharedPreferences'ta tutulur. */
final class Limits {
    private static final ConcurrentHashMap<String, String> LABELS = new ConcurrentHashMap<>();

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("limits", Context.MODE_PRIVATE);
    }

    static boolean has(Context c, String pkg) { return sp(c).contains("l_" + pkg); }

    static HashMap<String, Integer> all(Context c) {
        HashMap<String, Integer> m = new HashMap<>();
        for (Map.Entry<String, ?> e : sp(c).getAll().entrySet()) {
            String k = e.getKey();
            if (k.startsWith("l_") && e.getValue() instanceof Integer)
                m.put(k.substring(2), (Integer) e.getValue());
        }
        return m;
    }

    static void set(Context c, String pkg, int minutes) {
        sp(c).edit().putInt("l_" + pkg, minutes).apply();
        LimitService.poke();
        GuardService.start(c);
    }
    static void remove(Context c, String pkg) {
        sp(c).edit().remove("l_" + pkg).apply();
        if (all(c).isEmpty()) GuardService.stop(c);
    }

    static boolean hasPin(Context c) { return sp(c).contains("pin"); }

    static void setPin(Context c, String pin) {
        byte[] s = new byte[8];
        new SecureRandom().nextBytes(s);
        String salt = Base64.encodeToString(s, Base64.NO_WRAP);
        sp(c).edit().putString("salt", salt).putString("pin", hash(salt, pin)).apply();
    }

    static boolean checkPin(Context c, String pin) {
        SharedPreferences p = sp(c);
        String h = p.getString("pin", "");
        return MessageDigest.isEqual(hash(p.getString("salt", ""), pin).getBytes(), h.getBytes());
    }

    private static String hash(String salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Base64.encodeToString(md.digest((salt + pin).getBytes("UTF-8")), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    static String label(Context c, String pkg) {
        String l = LABELS.get(pkg);
        if (l == null) {
            try {
                PackageManager pm = c.getPackageManager();
                l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (Exception e) {
                l = pkg;
            }
            LABELS.put(pkg, l);
        }
        return l;
    }
}
