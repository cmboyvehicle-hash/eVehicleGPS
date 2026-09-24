package th.ev.gps;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ส่งตำแหน่งรถไปที่เว็บแอปทุก 1 นาที
 *  - ระบบรับเฉพาะรถที่ "เริ่มทริป" ในระบบแล้ว (ยังไม่เริ่ม → แสดง "รอเริ่มทริป" และรอไปก่อน)
 *  - เมื่อปิดทริปในระบบ → หยุดส่งเองอัตโนมัติ
 *  - ทำงานต่อแม้ปิดหน้าจอ (มีการแจ้งเตือนค้างไว้ตามข้อกำหนด Android)
 */
public class LocationService extends Service implements LocationListener {

    static final String PREF = "ev_gps";
    private static final String CH_ID = "ev_gps_tracking";
    private static final int NOTI_ID = 7101;
    private static volatile boolean running = false;

    private LocationManager lm;
    private Location best;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private PowerManager.WakeLock wake;
    private String plate;
    private long startedAt;

    /* ---------- เรียกจากหน้าจอ ---------- */
    static void start(Context c, String plate) {
        prefs(c).edit()
                .putString("plate", plate)
                .putBoolean("active", true)
                .putBoolean("everTrip", false)
                .putInt("sent", 0)
                .putLong("startedAt", System.currentTimeMillis())
                .putString("status", "กำลังหาตำแหน่ง GPS…")
                .apply();
        Intent i = new Intent(c, LocationService.class);
        c.startForegroundService(i);
    }

    static void stop(Context c, String reason) {
        prefs(c).edit().putBoolean("active", false)
                .putString("status", reason == null ? "หยุดส่งแล้ว" : reason).apply();
        c.stopService(new Intent(c, LocationService.class));
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, MODE_PRIVATE);
    }

    static boolean isRunning() { return running; }

    /* ---------- วงจรชีวิต ---------- */
    @SuppressLint("MissingPermission")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences p = prefs(this);
        plate = p.getString("plate", null);
        startedAt = p.getLong("startedAt", System.currentTimeMillis());
        if (plate == null || !p.getBoolean("active", false)) { stopSelf(); return START_NOT_STICKY; }

        createChannel();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTI_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTI_ID, buildNotification());
            }
        } catch (Exception e) {
            setStatus("ระบบมือถือไม่อนุญาตให้ทำงานเบื้องหลัง — เปิดแอปแล้วกดเริ่มใหม่");
            p.edit().putBoolean("active", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;

        if (wake == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "evgps:tracking");
            wake.setReferenceCounted(false);
            wake.acquire(14L * 60 * 60 * 1000);
        }

        if (lm == null) {
            lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            long ms = BuildConfig.SEND_INTERVAL_SEC * 1000L / 2;
            try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, ms, 0, this, Looper.getMainLooper()); } catch (Exception ignored) { }
            try { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, ms, 0, this, Looper.getMainLooper()); } catch (Exception ignored) { }
            try {
                best = better(lm.getLastKnownLocation(LocationManager.GPS_PROVIDER),
                              lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER));
            } catch (Exception ignored) { }
            handler.postDelayed(tick, 4000);
        }
        return START_STICKY;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            sendNow();
            handler.postDelayed(this, BuildConfig.SEND_INTERVAL_SEC * 1000L);
        }
    };

    @Override public void onLocationChanged(Location loc) { best = better(best, loc); }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

    private static Location better(Location a, Location b) {
        if (a == null) return b;
        if (b == null) return a;
        long dt = b.getTime() - a.getTime();
        if (dt > 90_000) return b;
        if (dt < -90_000) return a;
        return b.getAccuracy() <= a.getAccuracy() ? b : a;
    }

    private void sendNow() {
        final Location loc = best;
        if (loc == null) {
            setStatus("ยังจับสัญญาณ GPS ไม่ได้ — ตรวจว่าเปิด \"ตำแหน่ง\" ในมือถือ");
            return;
        }
        net.execute(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("action", "loc");
                b.put("plate", plate);
                b.put("lat", loc.getLatitude());
                b.put("lng", loc.getLongitude());
                b.put("acc", loc.hasAccuracy() ? loc.getAccuracy() : 0);
                b.put("speed", loc.hasSpeed() ? loc.getSpeed() : 0);
                b.put("heading", loc.hasBearing() ? loc.getBearing() : 0);
                b.put("time", loc.getTime());
                JSONObject r = new JSONObject(Net.post(BuildConfig.WEB_URL, b.toString()));
                SharedPreferences p = prefs(this);
                if (!r.optBoolean("ok")) {
                    setStatus("ส่งไม่สำเร็จ: " + r.optString("msg"));
                } else if (r.optBoolean("tracking", false)) {
                    int sent = p.getInt("sent", 0) + 1;
                    p.edit().putBoolean("everTrip", true).putInt("sent", sent).apply();
                    setStatus("✅ ส่งตำแหน่งแล้ว " + sent + " ครั้ง · ล่าสุด " + hhmm());
                } else if (p.getBoolean("everTrip", false)) {
                    // เคยอยู่ในทริป แล้วระบบบอกว่าไม่มีทริป = ปิดทริปแล้ว
                    handler.post(() -> stop(LocationService.this, "🏁 ปิดทริปแล้ว — หยุดส่งตำแหน่งอัตโนมัติ " + hhmm()));
                    return;
                } else {
                    long waitMin = (System.currentTimeMillis() - startedAt) / 60000;
                    if (waitMin >= BuildConfig.WAIT_TRIP_MAX_MIN) {
                        handler.post(() -> stop(LocationService.this,
                                "หยุดเอง: ไม่มีการเริ่มทริปในระบบภายใน " + BuildConfig.WAIT_TRIP_MAX_MIN + " นาที"));
                        return;
                    }
                    setStatus("⏳ รอเริ่มทริป — กด \"เริ่มทริป\" ในระบบ แล้วตำแหน่งจะขึ้นแผนที่เอง");
                }
            } catch (Exception e) {
                setStatus("📶 ไม่มีสัญญาณอินเทอร์เน็ต — จะลองใหม่ใน 1 นาที");
            }
        });
    }

    private void setStatus(String s) {
        prefs(this).edit().putString("status", s).putLong("statusAt", System.currentTimeMillis()).apply();
        handler.post(() -> {
            if (running) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTI_ID, buildNotification());
        });
    }

    private static String hhmm() {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CH_ID, "ส่งตำแหน่งรถ", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("แสดงขณะแอปกำลังส่งตำแหน่งรถไปยังแผนที่");
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH_ID)
                .setSmallIcon(R.drawable.ic_stat_car)
                .setContentTitle("📡 ส่งตำแหน่ง " + (plate == null ? "" : plate))
                .setContentText(prefs(this).getString("status", ""))
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (lm != null) { try { lm.removeUpdates(this); } catch (Exception ignored) { } }
        if (wake != null && wake.isHeld()) wake.release();
        net.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
