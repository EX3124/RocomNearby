package com.rocom.nearby.capture;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.rocom.nearby.R;
import com.rocom.nearby.map.MapPaint;
import com.rocom.nearby.map.NearbyRuntime;
import com.rocom.nearby.entity.NearbyEntity;
import com.rocom.nearby.net.NearbyPacketPipeline;
import com.rocom.nearby.net.SpeciesNames;
import com.rocom.nearby.net.SpeciesSizes;

import java.util.List;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class PcapCaptureService extends Service {

    private String publishedEntityLog = "";
    public static final String ACTION_START = "com.rocom.nearby.action.START_CAPTURE";
    public static final String ACTION_STOP = "com.rocom.nearby.action.STOP_CAPTURE";
    private static final int MIN_PORT = 1234;
    private static final int MAX_PORT = 1240;
    private static final long REMOTE_RETRY_DELAY_MS = 200;
    private final Object fileLock = new Object();
    private final PcapRecordCounter recordCounter = new PcapRecordCounter();
    private NearbyPacketPipeline packetPipeline;
    private volatile boolean running;
    private volatile boolean stopping;
    private volatile int listenPort;
    private volatile long bytesReceived;
    private volatile RemotePcapClient remoteClient;
    private volatile Thread remoteThread;
    private volatile Socket activeClient;
    private volatile ServerSocket serverSocket;
    private Thread serverThread;
    private FileOutputStream fileOutput;
    private File outputFile;
    private boolean globalHeaderWritten;
    private long lastProgressTick;

    @Override
    public void onCreate() {
        super.onCreate();
        SpeciesNames.load(this);
        SpeciesSizes.load(this);
        packetPipeline = new NearbyPacketPipeline(new NearbyPacketPipeline.Listener() {
            @Override
            public void onMapPosition(int sceneId, double x, double y) {
                MapPaint.record(sceneId, x, y);
                NearbyRuntime.publishPosition(sceneId, x, y);
            }

            @Override
            public void onSceneChanged(int sceneId) {
                NearbyRuntime.publishScene(sceneId);
                NearbyAlertNotification.releaseAll(PcapCaptureService.this);
            }

            @Override
            public void onEntities(List<NearbyEntity> entities) {
                String battle = "";
                if (!entities.isEmpty() && entities.get(0).fighting) {
                    NearbyEntity enemy = entities.get(0);
                    String enemyName = enemy.name == null ? "" : enemy.name;
                    battle = " battle=" + (enemyName.isEmpty() ? "unknown" : enemyName);
                }
                int nearbyCount = entities.size() - (battle.isEmpty() ? 0 : 1);
                String summary = nearbyCount + battle;
                if (!summary.equals(publishedEntityLog))
                    publishedEntityLog = summary;
                NearbyRuntime.publishEntities(entities);
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_START.equals(action)) {
            if (!enterForeground()) {
                CaptureRuntime.publish(CaptureRuntime.State.ERROR, 0, 0, null, "", 0, false);
                stopSelf();
                return START_NOT_STICKY;
            }
            if (running || stopping)
                return START_NOT_STICKY;
            if (packetPipeline != null)
                packetPipeline.reset();
            NearbyRuntime.clear();
            NearbyAlertNotification.releaseAll(PcapCaptureService.this);
            running = true;
            stopping = false;
            CaptureRuntime.publish(CaptureRuntime.State.STARTING, 0, 0, null, "", 0, false);
            CaptureTarget target = resolveCaptureTarget();
            if (target != null && target.isServer())
                startRemoteClient(target);
            else startServer();
        } else if (ACTION_STOP.equals(action)) {
            if (running)
                stopCapture();
            else
                stopSelf();
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
        closeRemoteClient();
        closeFileQuietly();
        if (packetPipeline != null)
            packetPipeline.reset();
        NearbyRuntime.clear();
        NearbyAlertNotification.releaseAll(this);
        leaveForeground();
        super.onDestroy();
    }

    private boolean enterForeground() {
        try {
            startForeground(CaptureNotification.NOTIFICATION_ID, CaptureNotification.build(this));
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private void leaveForeground() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {}
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
            CaptureRuntime.publish(CaptureRuntime.State.LISTENING, 0, 0, null, "", listenPort, false);
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
                    if (!running) break;
                    if (e instanceof SocketException) {
                        finishWithError();
                        break;
                    }
                    CaptureRuntime.publish(CaptureRuntime.State.LISTENING, bytesReceived, recordCounter.packets(), outputFile != null ? outputFile.getAbsolutePath() : null, "", listenPort, false);
                }
            }
        }, "nearby-pcap-receiver");
        serverThread.start();
    }

    @Nullable
    private CaptureTarget resolveCaptureTarget() {
        String raw = getSharedPreferences(CaptureTarget.PREFS_NAME, MODE_PRIVATE).getString(CaptureTarget.KEY_TARGET, null);
        CaptureTarget target = CaptureTarget.parse(raw);
        if (target != null)
            return target;
        return CaptureTarget.parse(getString(R.string.target_default));
    }

    private void startRemoteClient(CaptureTarget target) {
        remoteThread = new Thread(() -> runRemoteClient(target), "nearby-pcap-http-client");
        remoteThread.start();
    }

    private void runRemoteClient(CaptureTarget target) {
        while (ownsRemoteSession()) {
            RemotePcapClient client = new RemotePcapClient();
            remoteClient = client;
            try {
                InputStream body = client.open(target);
                if (!ownsRemoteSession()) return;
                if (!ensureOutputFile()) {
                    finishWithError();
                    return;
                }
                CaptureRuntime.publish(CaptureRuntime.State.CAPTURING, bytesReceived, recordCounter.packets(), outputFile != null ? outputFile.getAbsolutePath() : null, "", listenPort, true);
                if (consumePcapStream(body) <= 0)
                    throw new Throwable();
            } catch (Throwable ignored) {
            } finally {
                client.close();
                if (remoteClient == client)
                    remoteClient = null;
            }
            if (!ownsRemoteSession()) {
                return;
            }
            if (!sleepQuietly(REMOTE_RETRY_DELAY_MS))
                return;
        }
    }

    private boolean ownsRemoteSession() {
        return running && remoteThread == Thread.currentThread();
    }

    private long consumePcapStream(InputStream input) throws Throwable {
        long before = bytesReceived;
        byte[] first = new byte[24];
        int firstLength = readAtMost(input, first, 0, first.length);
        if (firstLength > 0) {
            boolean header = firstLength == first.length && looksLikePcapHeader(first);
            if (!header)
                writeBytes(first, firstLength);
            else if (!globalHeaderWritten) {
                writeBytes(first, firstLength);
                globalHeaderWritten = true;
            }
        }
        byte[] buffer = new byte[64 * 1024];
        int length;
        while (running && (length = input.read(buffer)) != -1) {
            if (length == 0) continue;
            writeBytes(buffer, length);
            publishProgress();
        }
        return bytesReceived - before;
    }

    private void closeRemoteClient() {
        RemotePcapClient client = remoteClient;
        remoteClient = null;
        if (client != null)
            client.close();
    }

    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (Throwable e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private ServerSocket bindServerSocket() {
        for (int port = MIN_PORT; port <= MAX_PORT; port++) {
            ServerSocket candidate = null;
            try {
                candidate = new ServerSocket();
                candidate.setReuseAddress(true);
                candidate.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4);
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
            CaptureRuntime.publish(CaptureRuntime.State.CAPTURING, bytesReceived, recordCounter.packets(), outputFile != null ? outputFile.getAbsolutePath() : null, "", listenPort, true);
            consumePcapStream(input);
            if (running) {
                if (bytesReceived == 0) {
                    stopCapture();
                    return;
                }
                CaptureRuntime.publish(CaptureRuntime.State.LISTENING, bytesReceived, recordCounter.packets(), outputFile != null ? outputFile.getAbsolutePath() : null, "", listenPort, false);
            }
        } catch (Throwable e) {
            if (running)
                CaptureRuntime.publish(CaptureRuntime.State.LISTENING, bytesReceived, recordCounter.packets(), outputFile != null ? outputFile.getAbsolutePath() : null, "", listenPort, false);
        }
    }

    private void writeBytes(byte[] data, int length) throws Throwable {
        synchronized (fileLock) {
            if (fileOutput == null)
                throw new Throwable();
            fileOutput.write(data, 0, length);
            fileOutput.flush();
        }
        recordCounter.feed(data, 0, length);
        if (packetPipeline != null) {
            try {
                packetPipeline.feed(data, 0, length);
            } catch (Throwable ignored) {}
        }
        bytesReceived += length;
    }

    private void publishProgress() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastProgressTick < 250) return;
        lastProgressTick = now;
        CaptureRuntime.publish(CaptureRuntime.State.CAPTURING, bytesReceived, recordCounter.packets(), outputFile != null ? outputFile.getAbsolutePath() : null, "", listenPort, true);
    }

    private boolean ensureOutputFile() {
        synchronized (fileLock) {
            if (fileOutput != null)
                return true;
            try {
                File directory = getExternalFilesDir("captures");
                if (directory == null)
                    directory = new File(getFilesDir(), "captures");
                if (!directory.exists() && !directory.mkdirs())
                    return false;
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                outputFile = new File(directory, "nearby_" + stamp + ".pcap");
                fileOutput = new FileOutputStream(outputFile, false);
                return true;
            } catch (Throwable e) {
                outputFile = null;
                fileOutput = null;
                return false;
            }
        }
    }

    private void stopCapture() {
        if (stopping) return;
        stopping = true;
        running = false;
        closeSocketQuietly(activeClient);
        closeSocketQuietly(serverSocket);
        closeRemoteClient();
        if (serverThread != null && serverThread != Thread.currentThread()) {
            try {
                serverThread.join(1500);
            } catch (Throwable e) {
                Thread.currentThread().interrupt();
            }
        }
        closeFileQuietly();
        if (packetPipeline != null)
            packetPipeline.reset();
        NearbyRuntime.clear();
        String finalPath = outputFile != null ? outputFile.getAbsolutePath() : null;
        if (outputFile != null && outputFile.length() == 0) {
            if (outputFile.delete())
                finalPath = null;
        }
        CaptureRuntime.publish(CaptureRuntime.State.IDLE, bytesReceived, recordCounter.packets(), finalPath, "", listenPort, false);
        stopSelf();
    }

    private void finishWithError() {
        if (stopping) return;
        stopping = true;
        running = false;
        closeSocketQuietly(activeClient);
        closeSocketQuietly(serverSocket);
        closeRemoteClient();
        if (serverThread != null && serverThread != Thread.currentThread()) {
            try {
                serverThread.join(1000);
            } catch (Throwable e) {
                Thread.currentThread().interrupt();
            }
        }
        closeFileQuietly();
        if (packetPipeline != null)
            packetPipeline.reset();
        NearbyRuntime.clear();
        CaptureRuntime.publish(CaptureRuntime.State.ERROR, bytesReceived, recordCounter.packets(), outputFile != null ? outputFile.getAbsolutePath() : null, "", listenPort, false);
        stopSelf();
    }

    private void closeFileQuietly() {
        synchronized (fileLock) {
            if (fileOutput != null) {
                try {
                    fileOutput.flush();
                } catch (Throwable ignored) {}
                try {
                    fileOutput.close();
                } catch (Throwable ignored) {}
                fileOutput = null;
            }
        }
    }

    private static void closeSocketQuietly(@Nullable java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Throwable ignored) {}
    }

    private static int readAtMost(InputStream input, byte[] destination, int offset, int length) throws Throwable {
        int total = 0;
        while (total < length) {
            int read = input.read(destination, offset + total, length - total);
            if (read == -1)
                break;
            total += read;
        }
        return total;
    }

    private static boolean looksLikePcapHeader(byte[] header) {
        int magic = ((header[0] & 0xff) << 24) | ((header[1] & 0xff) << 16) | ((header[2] & 0xff) << 8) | (header[3] & 0xff);
        return magic == 0xa1b2c3d4 || magic == 0xa1b23c4d || magic == 0xd4c3b2a1 || magic == 0x4d3cb2a1;
    }
}
