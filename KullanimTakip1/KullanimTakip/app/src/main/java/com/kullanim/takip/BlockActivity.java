package com.kullanim.takip;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Süre dolunca gösterilen basit ekran. */
public class BlockActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF121212);
        String name = getIntent().getStringExtra("name");
        int min = getIntent().getIntExtra("min", 0);

        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setBackgroundColor(0xFF121212);
        int p = Math.round(24 * getResources().getDisplayMetrics().density);
        l.setPadding(p, p, p, p);

        TextView t = new TextView(this);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(20);
        t.setGravity(Gravity.CENTER);
        t.setText("⏰\n\nSüre doldu\n\n" + name + " için bugünkü " + min + " dk limit doldu.\nLimit yarın sıfırlanır.");
        Button ok = new Button(this);
        ok.setText("Tamam");
        ok.setOnClickListener(v -> finish());
        l.addView(t);
        l.addView(ok);
        setContentView(l);
    }
}
