package org.simulpiscator.our_radio;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import static android.content.Context.ALARM_SERVICE;

class Alarm {
    private static PendingIntent sInstance = null;
    private static PendingIntent getIntent(Context context) {
        if (sInstance == null) {
            Intent intent = new Intent(context, AlarmReceiver.class);
            sInstance = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        }
        return sInstance;
    }
    static void schedule(Context context, long alarmTimeMs) {
        AlarmManager am = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        am.set(AlarmManager.RTC_WAKEUP, alarmTimeMs, getIntent(context));
    }
    static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        am.cancel(getIntent(context));
    }
}
