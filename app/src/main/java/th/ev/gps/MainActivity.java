package th.ev.gps;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * แอป e-Vehicle GPS — แอปแยกสำหรับส่งตำแหน่งรถ
 * 1) เลือกรถ  2) กด ▶ เริ่มส่งตำแหน่ง  3) เริ่มทริปในระบบ e-Vehicle ตามปกติ
 * แอปส่งตำแหน่งทุก 1 นาที และหยุดเองเมื่อปิดทริป
 */
public class MainActivity extends Activity {

    private static final int PINK = Color.parseColor("#EC008C");
    private static final int DEEP = Color.parseColor("#A21159");
    private static final int INK = Color.parseColor("#33202B");
    private static final int MUTED = Color.parseColor("#96738A");
    private static final int REQ_LOC = 11, REQ_NOTI = 12;

    private Spinner spinner;
    private TextView orgText, carInfo, statusText, statusTitle;
    private Button mainBtn;
    private LinearLayout statusCard;
    private final List<JSONObject> cars = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String pendingPlate;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(DEEP);
        setContentView(buildUi());
        loadCars();
        askBatteryOnce();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresher);
    }

    private final Runnable refresher = new Runnable() {
        @Override public void run() { refreshState(); ui.postDelayed(this, 2000); }
    };

    /* ================= หน้าจอ ================= */
    @SuppressLint("SetTextI18n")
    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#FFF6FB"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        sv.addView(root);

        // หัว
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setBackgroundColor(DEEP);
        head.setPadding(dp(20), dp(22), dp(20), dp(18));
        TextView t = text("📡 e-Vehicle GPS", 22, Color.WHITE, true);
        orgText = text("ส่งตำแหน่งรถระหว่างทริป", 13, Color.parseColor("#FFD6EC"), false);
        head.addView(t);
        head.addView(orgText);
        root.addView(head);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(16), dp(16), dp(24));
        root.addView(body);

        // เลือกรถ
        LinearLayout card = card();
        card.addView(text("1. เลือกรถที่ขับวันนี้", 15, DEEP, true));
        spinner = new Spinner(this);
        spinner.setPadding(0, dp(8), 0, dp(8));
        card.addView(spinner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        carInfo = text("กำลังโหลดรายชื่อรถ…", 13, MUTED, false);
        card.addView(carInfo);
        Button reload = flatButton("🔄 โหลดรายชื่อรถใหม่");
        reload.setOnClickListener(v -> loadCars());
        card.addView(reload);
        body.addView(card);
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { showCarInfo(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });

        // ปุ่มหลัก
        LinearLayout c2 = card();
        c2.addView(text("2. กดเริ่มส่งตำแหน่ง แล้วเริ่มทริปในระบบตามปกติ", 15, DEEP, true));
        mainBtn = new Button(this);
        mainBtn.setAllCaps(false);
        mainBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        mainBtn.setTypeface(Typeface.DEFAULT_BOLD);
        mainBtn.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        lp.topMargin = dp(10);
        c2.addView(mainBtn, lp);
        mainBtn.setOnClickListener(v -> onMainClick());
        body.addView(c2);

        // สถานะ
        statusCard = card();
        statusTitle = text("สถานะ", 15, DEEP, true);
        statusText = text("-", 15, INK, false);
        statusText.setPadding(0, dp(6), 0, 0);
        statusCard.addView(statusTitle);
        statusCard.addView(statusText);
        body.addView(statusCard);

        // ลิงก์
        Button map = flatButton("🗺️ เปิดแผนที่รถทั้งหมด");
        map.setOnClickListener(v -> openUrl(BuildConfig.WEB_URL + "?page=map"));
        body.addView(map);
        Button sys = flatButton("🚗 เปิดระบบ e-Vehicle (เริ่ม/ปิดทริป)");
        sys.setOnClickListener(v -> {
            String p = selectedPlate();
            openUrl(BuildConfig.WEB_URL + (p == null ? "" : "?role=driver&v=" + Uri.encode(p)));
        });
        body.addView(sys);

        TextView note = text("• แอปส่งตำแหน่งทุก 1 นาที เฉพาะช่วงที่รถคันนี้ \"เริ่มทริป\" ในระบบ\n" +
                "• ปิดทริปในระบบแล้ว แอปหยุดส่งเอง\n" +
                "• ปิดหน้าจอได้ตามปกติ ไม่ต้องเปิดแอปค้างไว้", 12.5f, MUTED, false);
        note.setPadding(dp(4), dp(14), dp(4), 0);
        body.addView(note);
        return sv;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.parseColor("#F2D8E5"));
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button flatButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(DEEP);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(24));
        bg.setStroke(dp(1), PINK);
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void styleMain(boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(on ? Color.parseColor("#C0392B") : Color.parseColor("#1E8E5A"));
        mainBtn.setBackground(bg);
        mainBtn.setText(on ? "■  หยุดส่งตำแหน่ง" : "▶  เริ่มส่งตำแหน่ง");
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    /* ================= รายชื่อรถ ================= */
    private void loadCars() {
        carInfo.setText("กำลังโหลดรายชื่อรถ…");
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSONObject r = new JSONObject(Net.get(BuildConfig.WEB_URL + "?api=vehicles"));
                if (!r.optBoolean("ok")) throw new Exception(r.optString("msg"));
                JSONArray arr = r.getJSONArray("vehicles");
                String org = r.optString("org", "");
                ui.post(() -> {
                    cars.clear();
                    for (int i = 0; i < arr.length(); i++) cars.add(arr.optJSONObject(i));
                    if (!org.isEmpty()) orgText.setText(org);
                    fillSpinner();
                });
            } catch (Exception e) {
                ui.post(() -> carInfo.setText("โหลดรายชื่อรถไม่สำเร็จ — ตรวจอินเทอร์เน็ต แล้วกดโหลดใหม่"));
            }
        });
    }

    private void fillSpinner() {
        List<String> labels = new ArrayList<>();
        for (JSONObject c : cars) {
            labels.add((c.optBoolean("trip") ? "🟢 " : "⚪ ") + c.optString("plate") + "  " + c.optString("model"));
        }
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        spinner.setAdapter(ad);
        String saved = LocationService.prefs(this).getString("plate", null);
        for (int i = 0; i < cars.size(); i++) {
            if (cars.get(i).optString("plate").equals(saved)) { spinner.setSelection(i); break; }
        }
        showCarInfo();
    }

    private String selectedPlate() {
        int i = spinner.getSelectedItemPosition();
        return (i >= 0 && i < cars.size()) ? cars.get(i).optString("plate") : null;
    }

    private void showCarInfo() {
        int i = spinner.getSelectedItemPosition();
        if (i < 0 || i >= cars.size()) { carInfo.setText(cars.isEmpty() ? "ไม่พบรายชื่อรถ" : ""); return; }
        JSONObject c = cars.get(i);
        carInfo.setText(c.optBoolean("trip")
                ? "🟢 อยู่ระหว่างทริป" + (c.optString("driver").isEmpty() ? "" : " · คนขับ " + c.optString("driver"))
                : "⚪ ยังไม่เริ่มทริป — เริ่มทริปในระบบ e-Vehicle ได้ทั้งก่อนหรือหลังกดเริ่มส่ง");
    }

    /* ================= เริ่ม/หยุด ================= */
    private void refreshState() {
        SharedPreferences p = LocationService.prefs(this);
        boolean on = p.getBoolean("active", false);
        styleMain(on);
        spinner.setEnabled(!on);
        String plate = p.getString("plate", "");
        statusTitle.setText(on ? "สถานะ · " + plate : "สถานะ");
        statusText.setText(p.getString("status", "ยังไม่ได้เริ่มส่งตำแหน่ง"));
    }

    private void onMainClick() {
        SharedPreferences p = LocationService.prefs(this);
        if (p.getBoolean("active", false)) {
            new AlertDialog.Builder(this)
                    .setTitle("หยุดส่งตำแหน่ง?")
                    .setMessage("รถจะไม่แสดงบนแผนที่จนกว่าจะกดเริ่มใหม่\n(ปกติแอปจะหยุดเองเมื่อปิดทริป)")
                    .setPositiveButton("หยุด", (d, w) -> { LocationService.stop(this, "หยุดส่งโดยผู้ใช้"); refreshState(); })
                    .setNegativeButton("ยกเลิก", null)
                    .show();
            return;
        }
        String plate = selectedPlate();
        if (plate == null) { Toast.makeText(this, "กรุณาเลือกรถก่อน", Toast.LENGTH_SHORT).show(); return; }
        startWithPermission(plate);
    }

    private void startWithPermission(String plate) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingPlate = plate;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingPlate = plate;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTI);
            return;
        }
        LocationService.start(this, plate);
        refreshState();
        Toast.makeText(this, "เริ่มส่งตำแหน่ง " + plate, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        String plate = pendingPlate;
        pendingPlate = null;
        if (code == REQ_LOC) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                if (plate != null) startWithPermission(plate);
            } else {
                new AlertDialog.Builder(this).setTitle("ต้องอนุญาตตำแหน่ง")
                        .setMessage("แอปต้องใช้ตำแหน่งเพื่อแสดงรถบนแผนที่ระหว่างทริป\nไปที่ ตั้งค่า > แอป > e-Vehicle GPS > สิทธิ์ > ตำแหน่ง > อนุญาต")
                        .setPositiveButton("เปิดการตั้งค่า", (d, w) -> startActivity(new Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))
                        .setNegativeButton("ปิด", null).show();
            }
        } else if (code == REQ_NOTI && plate != null) {
            LocationService.start(this, plate); // ไม่อนุญาตแจ้งเตือนก็ยังส่งตำแหน่งได้
            refreshState();
        }
    }

    /* ขอให้ไม่จำกัดแบตเตอรี่ (ครั้งเดียว) — กันมือถือหยุดแอปกลางทาง */
    @SuppressLint("BatteryLife")
    private void askBatteryOnce() {
        SharedPreferences p = LocationService.prefs(this);
        if (p.getBoolean("askedBattery", false)) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        p.edit().putBoolean("askedBattery", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("ตั้งค่าแบตเตอรี่")
                .setMessage("เพื่อให้ส่งตำแหน่งได้ต่อเนื่องขณะปิดหน้าจอ กรุณากด \"อนุญาต\" ให้แอปทำงานโดยไม่จำกัดแบตเตอรี่")
                .setPositiveButton("ตั้งค่า", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) { }
                })
                .setNegativeButton("ภายหลัง", null).show();
    }

    private void openUrl(String u) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); }
        catch (Exception e) { Toast.makeText(this, "เปิดลิงก์ไม่ได้", Toast.LENGTH_SHORT).show(); }
    }
}
