package th.ev.gps;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/**
 * เปิดจากลิงก์ในหน้าเว็บ (ปุ่ม "เปิดแอป GPS ส่งตำแหน่ง" หลังเริ่มทริปในเบราว์เซอร์)
 *   evgps://start?plate=<ทะเบียนรถ>
 * → ขอสิทธิ์ (ถ้ายังไม่เคยให้) → เริ่มส่งตำแหน่งรถคันนั้นทันที → แสดงหน้าสถานะ
 */
public class StartActivity extends Activity {

    private static final int REQ_LOC = 21, REQ_NOTI = 22;
    private String plate;
    private boolean askedNoti = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Uri u = getIntent() == null ? null : getIntent().getData();
        plate = u == null ? null : u.getQueryParameter("plate");
        if (plate == null || plate.trim().isEmpty()) { openMain(); return; }
        plate = plate.trim();
        go();
    }

    private void go() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && !askedNoti &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askedNoti = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTI);
            return;
        }
        LocationService.start(this, plate);
        Toast.makeText(this, "📡 เริ่มส่งตำแหน่ง " + plate, Toast.LENGTH_LONG).show();
        openMain();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_LOC
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "ต้องอนุญาตตำแหน่งก่อน จึงจะส่งตำแหน่งรถได้", Toast.LENGTH_LONG).show();
            openMain();
            return;
        }
        go();
    }

    private void openMain() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }
}
