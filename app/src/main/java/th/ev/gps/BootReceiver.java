package th.ev.gps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** เปิดเครื่องมือถือหรือเสียบที่ชาร์จแล้วให้ตัวเชื่อม Bluetooth ทำงานเอง ไม่ต้องเปิดแอป */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                || Intent.ACTION_POWER_CONNECTED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)) {
            BtLinkService.start(c);
        }
    }
}
