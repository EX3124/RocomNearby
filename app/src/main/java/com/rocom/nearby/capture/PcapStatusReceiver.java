package com.rocom.nearby.capture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class PcapStatusReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !PcapControl.ACTION_CAPTURE_STATUS.equals(intent.getAction())) return;
        boolean running = intent.getBooleanExtra("running", true);
        if (intent.getExtras() != null && intent.getExtras().get("running") instanceof String)
            running = Boolean.parseBoolean((String) intent.getExtras().get("running"));
        if (running) return;
        CaptureTarget target = CaptureTarget.parse(context.getSharedPreferences(CaptureTarget.PREFS_NAME, Context.MODE_PRIVATE).getString(CaptureTarget.KEY_TARGET, null));
        if (target != null && target.isServer()) return;
        try {
            Intent stop = new Intent(context, PcapCaptureService.class).setAction(PcapCaptureService.ACTION_STOP);
            context.startService(stop);
        } catch (Throwable ignored) {}
    }
}
