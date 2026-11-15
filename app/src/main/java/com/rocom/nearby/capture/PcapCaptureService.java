package com.rocom.nearby.capture;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Started service which receives PCAPdroid's TCP exporter stream and writes it
 * to an app-private pcap file.
 *
 * <p>The socket is opened *before* the control request is sent to PCAPdroid so
 * the exporter can connect as soon as the user grants access.</p>
 */
public class PcapCaptureService extends Service {
    private static final String TAG = "NearbyCapture";

    public static final String ACTION_START = "com.rocom.nearby.action.START_CAPTURE";
    public static final String ACTION_STOP = "com.rocom.nearby.action.STOP_CAPTURE";

    private static final int MIN_PORT = 1234;
    private static final int MAX_PORT = 1240;

    private final Object fileLock = new Object();
    private final PcapRecordCounter recordCounter = new PcapRecordCounter();

    private volatile boolean running;
    private volatile boolean stopping;
    private volatile int listenPort;
    private volatile long bytesReceived;
    private volatile Socket activeClient;
    private volatile ServerSocket serverSocket;
    private Thread serverThread;
    private FileOutputStream fileOutput;
    private File outputFile;
    private boolean globalHeaderWritten;
    private long lastProgressTick;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_START.equals(action)) {
            if (running || stopping) {
                return START_NOT_STICKY;
            }
            running = true;
            stopping = false;
            CaptureRuntime.publish(CaptureRuntime.State.STARTING, 0, 0, null,
                    "", 0, false);
            startServer();
        } else if (ACTION_STOP.equals(action)) {
            if (running) {
                stopCapture();
            } else {
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        closeSocketQuietly(activeClient);
        closeSocketQuietly(serverSocket);
        closeFileQuietly();
        super.onDestroy();
    }

    private void startServer() {
        serverThread = new Thread(() -> {
            ServerSocket socket = bindServerSocket();
            if (socket == null) {
                finishWithError();
                return;
            }
            serverSocket = socket;
            listenPort = socket.getLocalPort();
            CaptureRuntime.publish(CaptureRuntime.State.LISTENING, 0, 0, null,
                    "", listenPort, false);
            CaptureRuntime.notifyServerReady(listenPort);

            while (running) {
                try {
                    Socket client = socket.accept();
                    activeClient = client;
                    try {
                        receiveFromClient(client);
                    } finally {
                        closeSocketQuietly(client);
                        activeClient = null;
                    }
                } catch (Throwable e) {
                    if (!running) {
                        break;
                    }
                    if (e instanceof SocketException) {
                        Log.w(TAG, "Server socket closed unexpectedly", e);
                        finishWithError();
                        break;
                    }
                    Log.w(TAG, "Accept failed", e);
                    CaptureRuntime.publish(CaptureRuntime.State.LISTENING,
                            bytesReceived, recordCounter.packets(),
                            outputFile != null ? outputFile.getAbsolutePath() : null,
                            "", listenPort, false);
                }
            }
        }, "nearby-pcap-receiver");
        serverThread.start();
    }

    @Nullable
    private ServerSocket bindServerSocket() {
        for (int port = MIN_PORT; port <= MAX_PORT; port++) {
            ServerSocket candidate = null;
            try {
                candidate = new ServerSocket();
                candidate.setReuseAddress(true);
                candidate.bind(new InetSocketAddress(
                        InetAddress.getByName("127.0.0.1"), port), 4);
                return candidate;
            } catch (Throwable e) {
                closeSocketQuietly(candidate);
            }
        }
        return null;
    }

    private void receiveFromClient(Socket client) {
        try {
            client.setTcpNoDelay(true);
            client.setReceiveBufferSize(256 * 1024);
            InputStream input = new BufferedInputStream(client.getInputStream(), 64 * 1024);

            if (!ensureOutputFile()) {
                finishWithError();
                return;
            }

            CaptureRuntime.publish(CaptureRuntime.State.CAPTURING, bytesReceived,
                    recordCounter.packets(),
                    outputFile != null ? outputFile.getAbsolutePath() : null,
                    "", listenPort, true);

            /*
             * PCAPdroid writes a complete classic pcap stream per TCP
             * connection. Keep the first global header, and drop the header on
             * reconnect so the resulting file remains one valid pcap.
             */
            byte[] first = new byte[24];
            int firstLength = readAtMost(input, first, 0, first.length);
            if (firstLength > 0) {
                boolean header = firstLength == first.length && looksLikePcapHeader(first);
                if (header) {
                    if (!globalHeaderWritten) {
                        writeBytes(first, firstLength);
                        globalHeaderWritten = true;
                    }
                } else {
                    writeBytes(first, firstLength);
                }
            }

            byte[] buffer = new byte[64 * 1024];
            int length;
            while (running && (length = input.read(buffer)) != -1) {
                if (length == 0) {
                    continue;
                }
                writeBytes(buffer, length);
                publishProgress();
            }

            if (running) {
                if (bytesReceived == 0) {
                    // PCAPdroid accepted the socket but closed it before
                    // sending anything: its process is gone.
                    stopCapture();
                    return;
                }
                CaptureRuntime.publish(CaptureRuntime.State.LISTENING, bytesReceived,
                        recordCounter.packets(),
                        outputFile != null ? outputFile.getAbsolutePath() : null,
                        "", listenPort, false);
            }
        } catch (Throwable e) {
            if (running) {
                Log.w(TAG, "Receive failed", e);
                CaptureRuntime.publish(CaptureRuntime.State.LISTENING, bytesReceived,
                        recordCounter.packets(),
                        outputFile != null ? outputFile.getAbsolutePath() : null,
                        "", listenPort, false);
            }
        }
    }

    private void writeBytes(byte[] data, int length) throws IOException {
        synchronized (fileLock) {
            if (fileOutput == null) {
                throw new IOException("pcap output is not open");
            }
            fileOutput.write(data, 0, length);
            fileOutput.flush();
        }
        recordCounter.feed(data, 0, length);
        bytesReceived += length;
    }

    private void publishProgress() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastProgressTick < 250) {
            return;
        }
        lastProgressTick = now;
        CaptureRuntime.publish(CaptureRuntime.State.CAPTURING, bytesReceived,
                recordCounter.packets(),
                outputFile != null ? outputFile.getAbsolutePath() : null,
                "", listenPort, true);
    }

    private boolean ensureOutputFile() {
        synchronized (fileLock) {
            if (fileOutput != null) {
                return true;
            }
            try {
                File directory = getExternalFilesDir("captures");
                if (directory == null) {
                    directory = new File(getFilesDir(), "captures");
                }
                if (!directory.exists() && !directory.mkdirs()) {
                    return false;
                }
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                        .format(new Date());
                outputFile = new File(directory, "nearby_" + stamp + ".pcap");
                fileOutput = new FileOutputStream(outputFile, false);
                return true;
            } catch (Throwable e) {
                Log.e(TAG, "Cannot open capture file", e);
                outputFile = null;
                fileOutput = null;
                return false;
            }
        }
    }

    private void stopCapture() {
        if (stopping) {
            return;
        }
        stopping = true;
        running = false;
        closeSocketQuietly(activeClient);
        closeSocketQuietly(serverSocket);
        if (serverThread != null && serverThread != Thread.currentThread()) {
            try {
                serverThread.join(1500);
            } catch (Throwable e) {
                Thread.currentThread().interrupt();
            }
        }
        closeFileQuietly();

        String finalPath = outputFile != null ? outputFile.getAbsolutePath() : null;
        if (outputFile != null && outputFile.length() == 0) {
            // Nothing was received; don't leave an empty pcap behind.
            if (outputFile.delete()) {
                finalPath = null;
            }
        }
        CaptureRuntime.publish(CaptureRuntime.State.IDLE, bytesReceived,
                recordCounter.packets(), finalPath, "", listenPort, false);

        stopSelf();
    }

    private void finishWithError() {
        if (stopping) {
            return;
        }
        stopping = true;
        running = false;
        closeSocketQuietly(activeClient);
        closeSocketQuietly(serverSocket);
        if (serverThread != null && serverThread != Thread.currentThread()) {
            try {
                serverThread.join(1000);
            } catch (Throwable e) {
                Thread.currentThread().interrupt();
            }
        }
        closeFileQuietly();
        CaptureRuntime.publish(CaptureRuntime.State.ERROR, bytesReceived,
                recordCounter.packets(),
                outputFile != null ? outputFile.getAbsolutePath() : null,
                "", listenPort, false);
        stopSelf();
    }

    private void closeFileQuietly() {
        synchronized (fileLock) {
            if (fileOutput != null) {
                try {
                    fileOutput.flush();
                } catch (Throwable ignored) {
                }
                try {
                    fileOutput.close();
                } catch (Throwable ignored) {
                }
                fileOutput = null;
            }
        }
    }

    private static void closeSocketQuietly(@Nullable java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignored) {
        }
    }

    private static int readAtMost(InputStream input, byte[] destination, int offset,
                                  int length) throws IOException {
        int total = 0;
        while (total < length) {
            int read = input.read(destination, offset + total, length - total);
            if (read == -1) {
                break;
            }
            total += read;
        }
        return total;
    }

    private static boolean looksLikePcapHeader(byte[] header) {
        int magic = ((header[0] & 0xff) << 24)
                | ((header[1] & 0xff) << 16)
                | ((header[2] & 0xff) << 8)
                | (header[3] & 0xff);
        return magic == 0xa1b2c3d4 || magic == 0xa1b23c4d
                || magic == 0xd4c3b2a1 || magic == 0x4d3cb2a1;
    }

}
