package dev.shellmcp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/** 前台服务：常驻运行 MCP 服务器。 */
public class McpService extends Service {

    private static final String TAG = "McpService";
    private static final String CHANNEL_ID = "mcp_server";
    private static final int NOTIF_ID = 1;

    public static final String ACTION_START = "dev.shellmcp.START";
    public static final String ACTION_STOP  = "dev.shellmcp.STOP";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_LAN  = "lan";

    // 简单的全局状态，供界面读取
    public static volatile boolean RUNNING = false;
    public static volatile int PORT = 8000;
    public static volatile boolean LAN = false;
    public static volatile String LAST_ERROR = null;

    private McpServer server;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopServer();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        int port = intent != null ? intent.getIntExtra(EXTRA_PORT, 8000) : 8000;
        boolean lan = intent != null && intent.getBooleanExtra(EXTRA_LAN, false);
        PORT = port; LAN = lan; LAST_ERROR = null;

        createChannel();
        startForeground(NOTIF_ID, buildNotification(port, lan));

        try {
            server = new McpServer(port, lan);
            server.start();
            RUNNING = true;
            Log.i(TAG, "server started on " + port);
        } catch (Exception e) {
            RUNNING = false;
            LAST_ERROR = e.getMessage();
            Log.e(TAG, "start failed", e);
            stopForeground(true);
            stopSelf();
        }
        return START_STICKY;
    }

    private void stopServer() {
        RUNNING = false;
        if (server != null) {
            try { server.stop(); } catch (Exception ignore) {}
            server = null;
        }
    }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "MCP 服务", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("安卓 Shell MCP 服务运行状态");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(int port, boolean lan) {
        String host = lan ? "0.0.0.0" : "127.0.0.1";
        String text = "监听 http://" + host + ":" + port + "/mcp";
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("Shell MCP 正在运行")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setOngoing(true)
                .build();
    }

    public static void start(Context ctx, int port, boolean lan) {
        Intent i = new Intent(ctx, McpService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_LAN, lan);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.startService(new Intent(ctx, McpService.class).setAction(ACTION_STOP));
    }
}
