package th.ev.gps;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

/**
 * เชื่อม Bluetooth กับเครื่องประจำรถ (ESP32) แล้วรับคำสั่งเปิด-ปิดการส่งตำแหน่ง
 *
 * โปรโตคอล (ข้อความบรรทัดเดียว ปิดท้ายด้วย \n)
 *   ESP32 -> แอป :  GPS:ON|<ทะเบียน>|<คนขับ>|<วันที่>|<เวลา>
 *                   GPS:OFF|<ทะเบียน>
 *                   PING
 *   แอป -> ESP32 :  OK / GPS:STARTED / GPS:STOPPED
 *
 * เครื่อง ESP32 ตั้งชื่อ Bluetooth ว่า eVehicle-<ทะเบียนรถ>
 * ต้องจับคู่จากหน้าตั้งค่า Bluetooth ของมือถือก่อนหนึ่งครั้ง
 */
public class BtLinkService extends Service {

    private static final String CH_ID   = "ev_gps_bt";
    private static final int    NOTI_ID = 7102;
    private static final String NAME_PREFIX = "eVehicle-";
    private static final UUID   SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private volatile boolean alive = false;
    private Thread worker;

    static void start(Context c) {
        Intent i = new Intent(c, BtLinkService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
        else c.startService(i);
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, BtLinkService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (alive) return START_STICKY;
        alive = true;
        showNoti("รอเชื่อมกับเครื่องประจำรถ");
        worker = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "ev-btlink");
        worker.start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        alive = false;
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @SuppressWarnings("MissingPermission")
    private void loop() {
        while (alive) {
            BluetoothSocket s = null;
            try {
                BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                if (ad == null || !ad.isEnabled()) {
                    showNoti("Bluetooth ปิดอยู่");
                    napping(15000);
                    continue;
                }

                BluetoothDevice dev = null;
                Set<BluetoothDevice> bonded = ad.getBondedDevices();
                if (bonded != null) {
                    for (BluetoothDevice d : bonded) {
                        String n = d.getName();
                        if (n != null && n.startsWith(NAME_PREFIX)) { dev = d; break; }
                    }
                }
                if (dev == null) {
                    showNoti("ยังไม่ได้จับคู่เครื่องประจำรถ");
                    napping(20000);
                    continue;
                }

                showNoti("กำลังเชื่อม " + dev.getName());
                s = dev.createRfcommSocketToServiceRecord(SPP);
                ad.cancelDiscovery();
                s.connect();

                showNoti("เชื่อมแล้ว " + dev.getName());
                OutputStream out = s.getOutputStream();
                send(out, "OK");

                BufferedReader in = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), "UTF-8"));
                String line;
                while (alive && (line = in.readLine()) != null) {
                    handle(line.trim(), out);
                }
            } catch (Exception ignored) {
            } finally {
                try { if (s != null) s.close(); } catch (Exception ignored) { }
            }
            if (alive) {
                showNoti("หลุดการเชื่อมต่อ จะลองใหม่");
                napping(10000);
            }
        }
    }

    private void handle(String line, OutputStream out) {
        if (line.length() == 0) return;
        String[] a = line.split("\\|");
        String cmd = a[0];

        if ("GPS:ON".equals(cmd)) {
            String plate = a.length > 1 ? a[1].trim() : "";
            if (plate.length() == 0)
                plate = LocationService.prefs(this).getString("plate", "");
            if (plate != null && plate.length() > 0) {
                LocationService.start(this, plate);
                showNoti("เริ่มส่งตำแหน่ง " + plate);
                send(out, "GPS:STARTED");
            }

        } else if ("GPS:OFF".equals(cmd)) {
            LocationService.stop(this, "ปิดทริปแล้ว");
            showNoti("หยุดส่งตำแหน่ง");
            send(out, "GPS:STOPPED");

        } else if ("PING".equals(cmd)) {
            send(out, "OK");
        }
    }

    private void send(OutputStream out, String msg) {
        try {
            out.write((msg + "\n").getBytes("UTF-8"));
            out.flush();
        } catch (Exception ignored) { }
    }

    private void napping(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private void showNoti(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "เชื่อมเครื่องประจำรถ", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }

        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);

        Notification n = b.setContentTitle("เครื่องประจำรถ")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTI_ID, n);
        }
    }
}
