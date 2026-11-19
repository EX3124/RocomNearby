package com.rocom.nearby.capture;

import java.util.regex.Pattern;

public final class CaptureTarget {
    
    public static final String PREFS_NAME = "nearby";
    public static final String KEY_TARGET = "target";
    public static final int DEFAULT_SERVER_PORT = 8080;
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$");
    private static final Pattern IPV4_SHAPE = Pattern.compile(
            "^[0-9]{1,3}(\\.[0-9]{1,3}){3}$");
    private static final int MAX_PACKAGE_LENGTH = 150;
    private static final int MAX_PORT_LENGTH = 5;
    private static final int MAX_ADDRESS_LENGTH = 15;
    public enum Kind {
        PACKAGE,
        SERVER
    }
    public final Kind kind;
    public final String packageName;
    public final String host;
    public final int port;
    private CaptureTarget(Kind kind, String packageName, String host, int port) {
        this.kind = kind;
        this.packageName = packageName;
        this.host = host;
        this.port = port;
    }

    public static CaptureTarget packageTarget(String packageName) {
        return new CaptureTarget(Kind.PACKAGE, packageName, null, 0);
    }

    public static CaptureTarget server(String host, int port) {
        return new CaptureTarget(Kind.SERVER, null, host, port);
    }

    public boolean isServer() {
        return kind == Kind.SERVER;
    }

    public String describe() {
        return isServer() ? host + ":" + port : packageName;
    }
    
    public static CaptureTarget parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        CaptureTarget remote = parseServer(value);
        if (remote != null) {
            return remote;
        }
        if (isPackageName(value)) {
            return packageTarget(value);
        }
        return null;
    }

    public static boolean isValid(String raw) {
        return parse(raw) != null;
    }

    public static CaptureTarget parseServer(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        int colon = text.indexOf(':');
        String host = colon < 0 ? text : text.substring(0, colon);
        if (!isIpv4Address(host)) {
            return null;
        }
        int port = DEFAULT_SERVER_PORT;
        if (colon >= 0) {
            port = parsePort(text.substring(colon + 1));
            if (port <= 0) {
                return null;
            }
        }
        return server(host, port);
    }

    public static boolean isIpv4Address(String host) {
        if (host == null || host.isEmpty() || host.length() > MAX_ADDRESS_LENGTH) {
            return false;
        }
        if (!IPV4_SHAPE.matcher(host).matches()) {
            return false;
        }
        int segment = 0;
        int digits = 0;
        for (int i = 0; i <= host.length(); i++) {
            char c = i < host.length() ? host.charAt(i) : '.';
            if (c == '.') {
                if (digits == 0 || segment > 255) {
                    return false;
                }
                segment = 0;
                digits = 0;
                continue;
            }
            if (c < '0' || c > '9') {
                return false;
            }
            if (digits == 1 && segment == 0) {
                return false;
            }
            segment = segment * 10 + (c - '0');
            digits++;
        }
        return true;
    }

    public static int parsePort(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_PORT_LENGTH) {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
        }
        if (value < 1 || value > 65535) {
            return -1;
        }
        return value;
    }

    public static boolean isPackageName(String value) {
        return value != null
                && !value.isEmpty()
                && value.length() <= MAX_PACKAGE_LENGTH
                && PACKAGE_PATTERN.matcher(value).matches();
    }
}
