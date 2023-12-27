package org.simulpiscator.our_radio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = MainActivity.TAG + ":brec";
    private static final long ONE_MINUTE = 1000*60;
    @Override
    public void onReceive(Context context, Intent intent) {
        if(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Preferences prefs = Preferences.getInstance(context);
            long sleepTimeMs = prefs.getSleepTimeMs();
            if(sleepTimeMs >= 0) {
                long timeToAlarm = sleepTimeMs - System.currentTimeMillis();
                if(timeToAlarm < -ONE_MINUTE*3) // schedule alarm if it happened during reboot
                    prefs.setSleepTimeMs(-1);
                else
                    Alarm.schedule(context, sleepTimeMs);
            }
        }
    }
}
