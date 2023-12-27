package org.simulpiscator.our_radio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.net.SocketTimeoutException;

import static android.content.Context.POWER_SERVICE;

public class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = MainActivity.TAG + ":alrec";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        PowerManager pm = (PowerManager)context.getSystemService(POWER_SERVICE);
        final PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        wakeLock.acquire();
        new Thread() {
            public void run() {
                onReceiveAsync(context, wakeLock);
            }
        }.start();
    }

    private void onReceiveAsync(Context context, PowerManager.WakeLock wakeLock) {
        try {
            Log.d(TAG, "received sleep alarm, going to send stop request");

            Preferences prefs = Preferences.getInstance(context);
            prefs.setSleepTimeMs(-1);
            int timeout = prefs.getServerTimeoutMs();
            SocketConnection connection = new SocketConnection(prefs.getServerName(), prefs.getServerPort());
            if(!connection.waitForRead(timeout))
                throw new SocketTimeoutException();
            if(!connection.readLine().startsWith("OK "))
                throw new Exception("protocol error");
            int initialVolume = -1;
            MpdRequest request = new MpdRequest("status");
            if(!request.process(connection, timeout))
                throw new Exception(request.getError());
            MpdRequest.Result r = request.getResult();
            if (r != null && !r.isEmpty() && r.get(0).containsKey("volume:"))
                initialVolume = Integer.valueOf(r.get(0).get("volume:"));
            if (initialVolume > 0) {
                int duration = prefs.getSleepFadeDurationMs();
                if(duration > 0) {
                    int steps = prefs.getSleepFadeSteps();
                    for (int i = 0; i < steps; ++i) {
                        int volume = initialVolume - (initialVolume * i) / steps;
                        new MpdRequest("setvol " + Integer.toString(volume)).process(connection, timeout);
                        SystemClock.sleep(duration / steps);
                    }
                }
            }
            if(!new MpdRequest("stop").process(connection, timeout))
                Log.e(TAG, "failed to send stop request");
            if (initialVolume > 0)
                new MpdRequest("setvol " + Integer.toString(initialVolume)).process(connection, timeout);
            connection.close();
        } catch (Exception e) {
            Log.e(TAG, e.getClass() + ": " + e.getMessage());
        } finally {
            wakeLock.release();
        }
    }
}
