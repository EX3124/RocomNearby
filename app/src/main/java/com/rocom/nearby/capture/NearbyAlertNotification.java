package com.rocom.nearby.capture;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.rocom.nearby.MainActivity;
import com.rocom.nearby.R;
import com.rocom.nearby.entity.NearbyEntity;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class NearbyAlertNotification {

    private static final String TAG = "NearbyAlert";
    public static final String CHANNEL_ID = "nearby_special";
    private static final String BADGE_COLORFUL = "colorful";
    private static final String BADGE_SHINY = "shiny";
    private static final int SLOT_BASE = 0x2100;
    private static final int SLOT_COUNT = 0x400;
    private static final int SLOT_LIMIT = SLOT_BASE + SLOT_COUNT;
    private static final Map<String, Integer> ACTIVE = new LinkedHashMap<>();
    private static final ArrayDeque<Integer> FREE_SLOTS = new ArrayDeque<>();
    private static int nextSlot = SLOT_BASE;

    public static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.notification_title),
                NotificationManager.IMPORTANCE_HIGH);
        channel.enableVibration(true);
        channel.setShowBadge(true);
        manager.createNotificationChannel(channel);
    }

    @Nullable
    public static String announceBadge(Context context, @Nullable NearbyEntity entity,
                                       @Nullable String badgeKey) {
        if (context == null || entity == null) {
            return null;
        }
        String badge = alertBadge(badgeKey);
        if (badge == null || entity.fighting
                || !Double.isFinite(entity.worldX) || !Double.isFinite(entity.worldY)) {
            return null;
        }
        String key = alertKey(entity);
        synchronized (ACTIVE) {
            if (ACTIVE.containsKey(key)) {
                return key;
            }
            int id = allocateSlot(context);
            ACTIVE.put(key, id);
            post(context, entity, id, badgeLabel(badge));
        }
        return key;
    }

    public static void retain(Context context, @Nullable Collection<String> visibleKeys) {
        if (context == null) {
            return;
        }
        synchronized (ACTIVE) {
            Iterator<Map.Entry<String, Integer>> iterator = ACTIVE.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Integer> entry = iterator.next();
                if (visibleKeys != null && visibleKeys.contains(entry.getKey())) {
                    continue;
                }
                iterator.remove();
                FREE_SLOTS.addLast(entry.getValue());
                cancel(context, entry.getValue());
            }
        }
    }

    public static void releaseAll(Context context) {
        if (context == null) {
            return;
        }
        synchronized (ACTIVE) {
            for (Integer id : ACTIVE.values()) {
                cancel(context, id);
            }
            ACTIVE.clear();
            FREE_SLOTS.clear();
            nextSlot = SLOT_BASE;
        }
    }

    private static String alertKey(NearbyEntity entity) {
        return entity.identityKey();
    }

    @Nullable
    private static String alertBadge(@Nullable String badgeKey) {
        if (badgeKey == null) {
            return null;
        }
        String key = badgeKey.trim();
        if (BADGE_COLORFUL.equals(key)) {
            return BADGE_COLORFUL;
        }
        if (BADGE_SHINY.equals(key)) {
            return BADGE_SHINY;
        }
        return null;
    }

    private static int badgeLabel(String badge) {
        return BADGE_COLORFUL.equals(badge) ? R.string.badge_colorful : R.string.badge_shiny;
    }

    private static int allocateSlot(Context context) {
        Integer recycled = FREE_SLOTS.pollFirst();
        if (recycled != null) {
            return recycled;
        }
        if (nextSlot < SLOT_LIMIT) {
            return nextSlot++;
        }
        Iterator<Map.Entry<String, Integer>> iterator = ACTIVE.entrySet().iterator();
        if (iterator.hasNext()) {
            Map.Entry<String, Integer> oldest = iterator.next();
            iterator.remove();
            cancel(context, oldest.getValue());
            return oldest.getValue();
        }
        return SLOT_BASE;
    }

    private static void post(Context context, NearbyEntity entity, int id, int labelRes) {
        Intent intent = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_MAP, true)
                .putExtra(MainActivity.EXTRA_TRACK_ENTITY, entity.identityKey());
        PendingIntent pending = PendingIntent.getActivity(context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.notification_title))
                .setContentText(context.getString(R.string.notification_message,
                        context.getString(labelRes)))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pending);
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build());
        } catch (Throwable e) {
            Log.w(TAG, "Cannot post alert", e);
        }
    }

    private static void cancel(Context context, int id) {
        try {
            NotificationManagerCompat.from(context).cancel(id);
        } catch (Throwable e) {
            Log.w(TAG, "Cannot cancel alert", e);
        }
    }
}
