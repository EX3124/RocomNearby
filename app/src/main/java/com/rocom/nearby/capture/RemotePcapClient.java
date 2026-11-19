package com.rocom.nearby.capture;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

final class RemotePcapClient {
    
    private static final String PCAPNG_MARKER = "pcapng";
    private static final int BUFFER_SIZE = 64 * 1024;
    private HttpURLConnection connection;
    private InputStream body;

    InputStream open(CaptureTarget target) throws IOException {
        URL url = new URL("http", target.host, target.port, "/");
        HttpURLConnection http = (HttpURLConnection) url.openConnection();
        http.setReadTimeout(0);
        http.setUseCaches(false);
        http.setInstanceFollowRedirects(true);
        connection = http;
        try {
            int code = http.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new HttpStatusException(code);
            }
            String contentType = http.getContentType();
            if (contentType != null
                    && contentType.toLowerCase(Locale.US).contains(PCAPNG_MARKER)) {
                throw new PcapngStreamException();
            }
            body = new BufferedInputStream(http.getInputStream(), BUFFER_SIZE);
            return body;
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    void close() {
        InputStream input = body;
        body = null;
        if (input != null) {
            try {
                input.close();
            } catch (Throwable ignored) {
            }
        }
        HttpURLConnection http = connection;
        connection = null;
        if (http != null) {
            try {
                http.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    static final class HttpStatusException extends IOException {
        final int code;

        HttpStatusException(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    static final class PcapngStreamException extends IOException {
        PcapngStreamException() {
            super("pcapng stream");
        }
    }
}
