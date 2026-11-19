package com.rocom.nearby.capture;

import android.content.Intent;

public final class PcapControl {

    public static final String PCAPDROID_PACKAGE = "com.emanuelef.remote_capture";
    public static final String CAPTURE_CTRL_ACTIVITY =
            "com.emanuelef.remote_capture.activities.CaptureCtrl";
    public static final String ACTION_CAPTURE_STATUS =
            "com.emanuelef.remote_capture.CaptureStatus";
    public static final String EXTRA_ACTION = "action";
    public static final String ACTION_START = "start";
    public static final String ACTION_STOP = "stop";

    public static Intent buildStartIntent(String targetPackage, int localPort) {
        Intent intent = baseControlIntent(ACTION_START);
        intent.putExtra("pcap_dump_mode", "tcp_exporter");
        intent.putExtra("collector_ip_address", "127.0.0.1");
        intent.putExtra("collector_host", "127.0.0.1");
        intent.putExtra("collector_port", localPort);
        intent.putExtra("app_filter", targetPackage);
        intent.putExtra("dump_extensions", true);
        intent.putExtra("full_payload", true);
        intent.putExtra("ip_mode", "both");
        intent.putExtra("snaplen", 262144);
        intent.putExtra("broadcast_receiver",
                "com.rocom.nearby.capture.PcapStatusReceiver");
        return intent;
    }

    public static Intent buildStopIntent() {
        Intent intent = baseControlIntent(ACTION_STOP);
        
        intent.putExtra("broadcast_receiver",
                "com.rocom.nearby.capture.PcapStatusReceiver");
        return intent;
    }

    private static Intent baseControlIntent(String action) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setClassName(PCAPDROID_PACKAGE, CAPTURE_CTRL_ACTIVITY);
        intent.putExtra(EXTRA_ACTION, action);
        return intent;
    }
}
