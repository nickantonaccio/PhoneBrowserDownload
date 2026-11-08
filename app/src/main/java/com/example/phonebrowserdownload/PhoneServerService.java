package com.example.phonebrowserdownload;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

public class PhoneServerService extends Service {
    static final String START = "com.example.phonebrowserdownload.START";
    static final String STOP = "com.example.phonebrowserdownload.STOP";
    static volatile Info info = new Info(false, "", "", null);
    private FileServer server;

    static final class Info {
        final boolean running;
        final String addresses, password, error;
        Info(boolean running, String addresses, String password, String error) {
            this.running = running; this.addresses = addresses; this.password = password; this.error = error;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("share", "File sharing", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (server != null) return START_NOT_STICKY;
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(7, n);
        try {
            if (intent == null || intent.getStringExtra("folder") == null) throw new IllegalArgumentException("No folder selected");
            server = new FileServer(getContentResolver(), Uri.parse(intent.getStringExtra("folder")));
            server.start();
            info = new Info(true, FileServer.localAddresses(server.getPort()), server.getPassword(), null);
        } catch (Exception e) {
            info = new Info(false, "", "", e.toString());
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        Intent ui = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, ui, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "share")
                .setContentTitle("Phone file sharing is active")
                .setContentText("Open to see address or stop sharing")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(pending)
                .setOngoing(true).build();
    }

    @Override public void onDestroy() {
        if (server != null) { server.close(); server = null; }
        info = new Info(false, "", "", null);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
