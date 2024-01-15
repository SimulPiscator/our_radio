package org.simulpiscator.our_radio;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import static android.content.Context.ALARM_SERVICE;

class Alarm {
    private PendingIntent mInstance = null;
    private final AlarmManager mAlarmManager;
    private final Context mContext;

    public Alarm(Context context) {
        mContext = context;
        mAlarmManager = (AlarmManager) context.getSystemService(ALARM_SERVICE);
    }
    private PendingIntent getIntent() {
        if (mInstance == null) {
            Intent intent = new Intent(mContext, AlarmReceiver.class);
            mInstance = PendingIntent.getBroadcast(mContext, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        }
        return mInstance;
    }
    void schedule(long alarmTimeMs) {
        mAlarmManager.set(AlarmManager.RTC_WAKEUP, alarmTimeMs, getIntent());
    }
    void cancel() {
        mAlarmManager.cancel(getIntent());
    }
}
