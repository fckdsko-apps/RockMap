package com.rockmap.app.field;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;


public final class TrackRecordingService extends Service implements LocationListener {
    public static final String ACTION_START = "com.rockmap.app.field.START_TRACK";
    public static final String ACTION_PAUSE = "com.rockmap.app.field.PAUSE_TRACK";
    public static final String ACTION_RESUME = "com.rockmap.app.field.RESUME_TRACK";
    public static final String ACTION_STOP = "com.rockmap.app.field.STOP_TRACK";
    public static final String EXTRA_TRACK_ID = "track_id";
    private static final String CHANNEL = "rockmap-track-recording";
    private static final int NOTIFICATION_ID = 7301;

    private LocationManager locationManager;
    private FieldDatabase database;
    private long trackId = -1L;
    private boolean paused;

    @Override public void onCreate() {
        super.onCreate();
        database = FieldDatabase.get(this);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        ensureChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        long requestedId = intent.getLongExtra(EXTRA_TRACK_ID, trackId);
        if (requestedId > 0L) trackId = requestedId;

        if (ACTION_START.equals(action)) {
            if (trackId <= 0L) {
                stopSelf();
                return START_NOT_STICKY;
            }
            paused = false;
            startForeground(NOTIFICATION_ID, notification("Recording track"));
            requestUpdates();
            database.setTrackStatus(trackId, FieldDatabase.TRACK_RECORDING, 0L);
        } else if (ACTION_PAUSE.equals(action)) {
            if (trackId > 0L) {
                paused = true;
                removeUpdates();
                database.setTrackStatus(trackId, FieldDatabase.TRACK_PAUSED, 0L);
                notifyState("Track paused");
            }
        } else if (ACTION_RESUME.equals(action)) {
            if (trackId > 0L) {
                startForeground(NOTIFICATION_ID, notification("Recording track"));
                paused = false;
                database.setTrackStatus(trackId, FieldDatabase.TRACK_RECORDING, 0L);
                requestUpdates();
                notifyState("Recording track");
            }
        } else if (ACTION_STOP.equals(action)) {
            finishTrack();
        }
        return START_NOT_STICKY;
    }

    private void requestUpdates() {
        if (trackId <= 0L || paused) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            database.setTrackStatus(trackId, FieldDatabase.TRACK_INTERRUPTED, System.currentTimeMillis());
            stopForeground(true);
            stopSelf();
            return;
        }
        try {
            locationManager.removeUpdates(this);
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 2f, this);
        } catch (RuntimeException ex) {
            database.setTrackStatus(trackId, FieldDatabase.TRACK_INTERRUPTED, System.currentTimeMillis());
            stopForeground(true);
            stopSelf();
        }
    }

    private void finishTrack() {
        removeUpdates();
        if (trackId > 0L) database.setTrackStatus(trackId, FieldDatabase.TRACK_COMPLETE, System.currentTimeMillis());
        stopForeground(true);
        stopSelf();
    }

    private void removeUpdates() {
        try { locationManager.removeUpdates(this); } catch (RuntimeException ignored) {}
    }

    @Override public void onLocationChanged(Location location) {
        if (paused || trackId <= 0L || location == null) return;
        double lat = location.getLatitude();
        double lon = location.getLongitude();
        if (!Double.isFinite(lat) || !Double.isFinite(lon)
                || lat < -90d || lat > 90d || lon < -180d || lon > 180d) return;
        float accuracy = location.hasAccuracy() && Float.isFinite(location.getAccuracy())
                ? location.getAccuracy() : -1f;
        double altitude = location.hasAltitude() ? location.getAltitude() : Double.NaN;
        long time = location.getTime() > 0L ? location.getTime() : System.currentTimeMillis();
        database.addTrackPoint(trackId, new GeoMath.Point(lat, lon, altitude, accuracy, time));
    }

    @Override public void onProviderDisabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider) && trackId > 0L) {
            removeUpdates();
            database.setTrackStatus(trackId, FieldDatabase.TRACK_INTERRUPTED, System.currentTimeMillis());
            notifyState("Track interrupted — GPS disabled");
            stopForeground(true);
            stopSelf();
        }
    }
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Track recording", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shown only while RockMap is actively recording a GPS track.");
        manager.createNotificationChannel(channel);
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, FieldActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        builder.setContentTitle("RockMap")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .setContentIntent(pending)
                .setCategory(Notification.CATEGORY_SERVICE);
        return builder.build();
    }

    private void notifyState(String text) {
        if (trackId <= 0L) return;
        // This is the foreground-service notification itself, not an independent app
        // notification. Re-post it through Service.startForeground() so track recording
        // does not need Android 13's POST_NOTIFICATIONS runtime permission.
        startForeground(NOTIFICATION_ID, notification(text));
    }

    @Override public void onDestroy() {
        removeUpdates();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
