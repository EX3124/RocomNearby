package com.rocom.nearby.capture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Receives the explicit status broadcast PCAPdroid sends when its capture
 * stops, for example when the user presses stop in the PCAPdroid UI or the VPN
 * permission is revoked. The receiver makes sure our foreground receiver is
 * torn down too instead of waiting forever for another TCP connection.
 */
public class PcapStatusReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !PcapControl.ACTION_CAPTURE_STATUS.equals(intent.getAction())) {
            return;
        }
        boolean running = intent.getBooleanExtra("running", true);
        // Some senders (and adb `am broadcast -e running false`) deliver the
        // value as a string rather than a boolean. Accept both forms.
        if (intent.getExtras() != null
                && intent.getExtras().get("running") instanceof String) {
            running = Boolean.parseBoolean((String) intent.getExtras().get("running"));
        }
        if (running) {
            return;
        }
        try {
            Intent stop = new Intent(context, PcapCaptureService.class)
                    .setAction(PcapCaptureService.ACTION_STOP);
            context.startService(stop);
        } catch (Throwable ignored) {
            // The service is not running (or the app is in the background on
            // an OS version which disallows starting it). Nothing to clean up.
        }
    }
}
