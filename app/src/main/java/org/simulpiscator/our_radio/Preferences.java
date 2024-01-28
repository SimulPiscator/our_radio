package org.simulpiscator.our_radio;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class Preferences {

    private static final String KEY_MPD_SERVER_IP = "pref_mpd_server_ip";
    private static final String KEY_MPD_SERVER_PORT = "pref_mpd_server_port";
    private static final String KEY_WS_PUSH_PORT = "pref_ws_push_port";
    private static final String KEY_ENABLE_WIFI = "pref_enable_wifi";
    private static final String KEY_PLAYLIST_PATTERN = "pref_playlist_pattern";
    private static final String KEY_OUTPUTS = "pref_outputs";
    private static final String KEY_SLEEP_FADE_DURATION = "pref_sleep_fade_duration";
    private static final String KEY_CROSS_FADE_DURATION = "pref_cross_fade_duration";
    private static final String KEY_SHUFFLE = "pref_playlist_shuffle";
    private static final String KEY_REPEAT = "pref_playlist_repeat";
    private static final String KEY_SLEEP_TIME = "pref_sleep_time";
    private static final String KEY_INIT_REQUIRED = "pref_init_required";

    private static final int SERVER_TIMEOUT_MS = 10000; // long timeout required for loading playlist
    private static final int WIFI_TIMEOUT_MS = 15000;
    private static final int SLEEP_FADE_STEPS = 32;

    static class AudioOutput {
        String name, id;
        int enabled;
    }

    public static class Activity
            extends PreferenceActivity
            implements Preference.OnPreferenceChangeListener {

        private static void recursivelyAddPreferences(Preference pref, List<Preference> prefs) {
            prefs.add(pref);
            if (pref instanceof PreferenceGroup) {
                PreferenceGroup group = (PreferenceGroup) pref;
                for (int i = 0; i < group.getPreferenceCount(); ++i)
                    recursivelyAddPreferences(group.getPreference(i), prefs);
            }
        }

        List<Preference> getAllPreferences() {
            List<Preference> prefs = new ArrayList<Preference>();
            recursivelyAddPreferences(getPreferenceScreen(), prefs);
            return prefs;
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.preferences);
            List<Preference> prefs = getAllPreferences();
            for (Preference pref : prefs) {
                onPreferenceChange(pref, null);
                if(pref.getTitle() == null || pref.getTitle().length() == 0)
                    getPreferenceScreen().removePreference(pref);
                else
                    pref.setOnPreferenceChangeListener(this);
            }
        }

        @Override
        public boolean onPreferenceChange(Preference pref, Object value) {
            if (pref instanceof EditTextPreference) {
                String summary;
                if(value != null)
                    summary = value.toString();
                else
                    summary = ((EditTextPreference) pref).getText();
                if(pref.getKey() != null && pref.getKey().endsWith("_duration"))
                    summary += "s";
                pref.setSummary(summary);
            } else if(pref instanceof ListPreference) {
                CharSequence summary = "";
                if(value == null) {
                    summary = ((ListPreference) pref).getEntry();
                } else {
                    int idx = ((ListPreference) pref).findIndexOfValue(value.toString());
                    if(idx >= 0)
                        summary = ((ListPreference) pref).getEntries()[idx];
                }
                pref.setSummary(summary);
            }
            return true;
        }
    }

    private final SharedPreferences mPreferences;

    private static Preferences sInstance;

    static Preferences getInstance(Context context) {
        if (sInstance == null)
            sInstance = new Preferences(context);
        return sInstance;
    }

    private Preferences(Context context) {
        mPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    }

    boolean initRequired() {
        return mPreferences.getBoolean(KEY_INIT_REQUIRED, true);
    }

    void edit(Context context) {
        mPreferences.edit().putBoolean(KEY_INIT_REQUIRED, false).apply();
        Intent intent = new Intent(context, Activity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        context.startActivity(intent);
    }

    String getServerName() {
        return mPreferences.getString(KEY_MPD_SERVER_IP, "");
    }

    int getServerPort() {
        String port = mPreferences.getString(KEY_MPD_SERVER_PORT, "6600");
        try {
            return Integer.decode(port);
        } catch (Exception e) {
            return 0;
        }
    }

    int getServerWsPort() {
        String port = mPreferences.getString(KEY_WS_PUSH_PORT, "3688");
        try {
            return Integer.decode(port);
        } catch (Exception e) {
            return 0;
        }
    }

    int getServerTimeoutMs() {
        return SERVER_TIMEOUT_MS;
    }

    enum WifiOptions { leave, enable, ask };
    WifiOptions getEnableWifi() {
        String s = mPreferences.getString(KEY_ENABLE_WIFI, "");
        if(s.equals("0")) {
            return WifiOptions.leave;
        } else if (s.equals("1")) {
            return WifiOptions.enable;
        } else {
            return WifiOptions.ask;
        }
    }

    void setEnableWifi(WifiOptions opt) {
        String s = "";
        switch(opt) {
            case leave:
                s = "0";
                break;
            case enable:
                s = "1";
                break;
            case ask:
                s = "";
                break;
        }
        mPreferences.edit().putString(KEY_ENABLE_WIFI, s).apply();
    }

    int getWifiTimeoutMs() { return WIFI_TIMEOUT_MS; }

    String getPlaylistPattern() {
        String s = mPreferences.getString(KEY_PLAYLIST_PATTERN, "");
        if(s.isEmpty())
            s = ".*";
        return s;
    }

    boolean getShuffle() {
        return mPreferences.getBoolean(KEY_SHUFFLE, false);
    }

    boolean getRepeat() {
        return mPreferences.getBoolean(KEY_REPEAT, false);
    }

    long getSleepTimeMs() { return mPreferences.getLong(KEY_SLEEP_TIME, -1); }

    void setSleepTimeMs(long ms) { mPreferences.edit().putLong(KEY_SLEEP_TIME, ms).apply(); }

    int getCrossFadeDurationMs() {
        String duration = mPreferences.getString(KEY_CROSS_FADE_DURATION, "0");
        try {
            return Integer.decode(duration) * 1000;
        } catch (Exception e) {
            return 0;
        }
    }

    int getSleepFadeDurationMs() {
        String duration = mPreferences.getString(KEY_SLEEP_FADE_DURATION, "6");
        try {
            int ms = Integer.decode(duration) * 1000;
            if (ms >= 10*1000)
                ms = 9*1000;
            return ms;
        } catch (Exception e) {
            return 6*1000;
        }
    }

    int getSleepFadeSteps() {
        return SLEEP_FADE_STEPS;
    }

    boolean isOutputVisible(AudioOutput output) {
        String s = mPreferences.getString(KEY_OUTPUTS, "");
        DynamicMultiselectPreference.Data data =
                DynamicMultiselectPreference.unserializeData(s);
        int length = data.ids.length;
        boolean result = true;
        boolean updatePreferenceData = true;
        boolean found = false;
        for (int i = 0; i < length; ++i) {
            if (output.id.equals(data.ids[i])) {
                if (output.name.equals(data.names[i]))
                    updatePreferenceData = false;
                else
                    data.names[i] = output.name;
                result = data.checked[i];
                found = true;
                break;
            }
        }
        if (!found) {
            data.ids = Arrays.copyOf(data.ids, length + 1);
            data.names = Arrays.copyOf(data.names, length + 1);
            data.checked = Arrays.copyOf(data.checked, length + 1);
            data.ids[length] = output.id;
            data.names[length] = output.name;
            data.checked[length] = true;
        }
        if (updatePreferenceData) {
            s = DynamicMultiselectPreference.serializeData(data);
            mPreferences.edit().putString(KEY_OUTPUTS, s).apply();
        }
        return result;
    }
}
