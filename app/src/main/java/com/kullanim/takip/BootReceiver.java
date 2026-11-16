package com.kullanim.takip;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Açılışta ve uygulama güncellenince yedek korumayı yeniden başlatır. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        GuardService.start(c);
    }
}
