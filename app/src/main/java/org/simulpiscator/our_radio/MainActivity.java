package org.simulpiscator.our_radio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

import static android.view.View.inflate;
import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;

public class MainActivity extends Activity implements MpdThread.Listener, WsThread.Listener {

    static final String TAG = "sp:radio";

    private static final String FILE_TAG = "file:";
    private static final String STATION_TAG = "title:";
    private static final String PROGRAM_TAG = "album:";
    private static final String AUTHOR_TAG = "artist:";

    private static final int LENGTH_PERSISTENT = -1;
    private static final int mUIUpdateDelayMs = 1000;

    enum State {idle, playingInitiated, playing, stopInitiated, error}

    static private class PlayerState {
        String station = "", program = "", author = "";
        volatile State state = State.idle;
        int volume = -1;
        ArrayList<Preferences.AudioOutput> outputs = new ArrayList<>();
    }

    private final PlayerState mState = new PlayerState();
    private Preferences mPreferences;

    private boolean mIsActive = false;
    private SocketConnection mConnection;
    private int mServerTimeoutMs;
    private MpdThread mMpdThread;
    private WsThread mWsThread;

    private Handler mUIThreadHandler;

    private static class StatusMessage {
        StatusMessage(Context context, String text, int length) {
            mPersistent = (length == LENGTH_PERSISTENT);
            mToast = Toast.makeText(context, text, mPersistent ? LENGTH_SHORT : length);
        }
        void show() { mToast.show(); }
        void cancel() { mToast.cancel(); }
        boolean isPersistent() { return mPersistent; }

        private final Toast mToast;
        private final boolean mPersistent;
    }
    private StatusMessage mStatusMessage;

    private TextView mStationView, mProgramView, mAuthorView, mRemainingSleepView;
    private Button mPlayButton, mStopButton;
    private SeekBar mVolumeBar;
    private LinearLayout mOutputs;

    class BackgroundThread extends HandlerThread {
        private final Object mLock = new Object();
        BackgroundThread() {
            super(MainActivity.TAG + ":bg");
            start();
            synchronized (mLock) {
                try {
                    mLock.wait();
                } catch (InterruptedException e) {
                }
            }
        }
        @Override
        protected void onLooperPrepared() {
            mHandler = new Handler(getLooper());
            synchronized (mLock) {
                mLock.notify();
            }
        }
        void post(Runnable runnable) {
            mHandler.post(runnable);
        }
        private volatile Handler mHandler;
    }
    private BackgroundThread mBackgroundThread;
    private <V extends Object> V runInBackground(final Callable<V> callable) throws Exception {
        class Data { volatile V result; volatile Exception error; };
        final Data data = new Data();
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                try {
                    data.result = callable.call();
                } catch (Exception e) {
                    data.error = e;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        throw new RuntimeException();
                    }
                });
            }
        };
        mBackgroundThread.post(runnable);
        try { Looper.loop(); } catch(RuntimeException ignored) {}
        if(data.error != null)
            throw data.error;
        return data.result;
    }

    @Override
    public void onMpdError(Exception error) {
        onError(error);
    }

    @Override
    public void onWsError(Exception error) { onError(error); }

    private void onError(final Exception error) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                setStatusMessage(formatExceptionMessage(error), LENGTH_LONG);
            }
        });
    }

    private void onNotification(final String notification) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                setStatusMessage(notification, LENGTH_SHORT);
            }
        });
    }

    @Override
    public void onWsSubsystemChange(String subsystem) {
        if(mState.state != State.error) {
            try {
                updatePlayerState();
            } catch(Exception e) {
                onError(e);
            }
        }
    }

    private static String formatExceptionMessage(Exception e) {
        String s = "";
        if(e instanceof NullPointerException) {
            StackTraceElement frame = e.getStackTrace()[0];
            s = String.format(Locale.US, "null pointer at %s:%d", frame.getFileName(), frame.getLineNumber());
        } else {
            s = e.getClass().getSimpleName();
            s = s.replaceAll("[A-Z][^A-Z]", " $0");
            s = s.replaceAll("Exception", "");
            s = s.trim();
            String msg = e.getLocalizedMessage();
            if (msg == null || msg.isEmpty())
                msg = e.getMessage();
            if (msg == null)
                msg = "";
            if (!msg.isEmpty()) {
                if (s.isEmpty())
                    s = msg;
                else
                    s = s + ": " + msg;
            }
        }
        return s;
    }

    private void clearStatusMessage() {
        if(mStatusMessage != null) {
            mStatusMessage.cancel();
            mStatusMessage = null;
        }
    }

    private void setStatusMessage(String msg, int length) {
        clearStatusMessage();
        if(msg != null) {
            mStatusMessage = new StatusMessage(this, msg, length);
            mStatusMessage.show();
        }
    }

    private void updatePlayerState() throws Exception {
        if (mConnection == null)
            return;

        synchronized (mState) {
            if (mState.state != State.error) {
                MpdRequest request = new MpdRequest("status");
                if(request.process(mConnection, mServerTimeoutMs)) {
                    MpdRequest.Result r = request.getResult();
                    if(r.isEmpty())
                        throw new MpdRequest.ProtocolErrorException("non-empty result expected");

                    final HashMap<String, String> entry = r.get(0);
                    if (entry.containsKey("volume:"))
                        mState.volume = Integer.decode(entry.get("volume:"));
                    if (entry.containsKey("state:")) {
                        String s = entry.get("state:");
                        if (s.equals("play")) {
                            mState.state = State.playing;
                        } else {
                            mState.state = State.idle;
                        }
                    }
                }
                MpdRequest.Result r = null;
                if (mState.state == State.playing) {
                    request = new MpdRequest("currentsong");
                    if(request.process(mConnection, mServerTimeoutMs) && !request.getResult().isEmpty()) {
                        r = request.getResult();
                    }
                }
                if (r == null)
                    r = new MpdRequest.Result();
                if (r.isEmpty())
                    r.add(new HashMap<String, String>());
                HashMap<String, String> info = r.get(0);
                mState.station = info.get(STATION_TAG);
                mState.program = info.get(PROGRAM_TAG);
                mState.author = info.get(AUTHOR_TAG);
                if (mState.station == null)
                    mState.station = "";
                if (mState.program == null)
                    mState.program = "";
                if (mState.author == null)
                    mState.author = "";
                if (mState.state != State.playing) { // avoid stale data
                    mState.program = "";
                    mState.author = "";
                } else {
                    if (mState.station.equals(mState.program))
                        mState.program = "";
                    if (mState.station.equals(mState.author))
                        mState.author = "";
                }
                mState.station = cleanupString(mState.station);
                mState.program = cleanupString(mState.program);
                mState.author = cleanupString(mState.author);

                mState.outputs = new ArrayList<Preferences.AudioOutput>();
                request = new MpdRequest("outputs");
                if (request.process(mConnection, mServerTimeoutMs)) {
                    for (HashMap<String, String> entry : request.getResult()) {
                        Preferences.AudioOutput output = new Preferences.AudioOutput();
                        output.id = entry.get("outputid:");
                        output.name = entry.get("outputname:");
                        output.enabled = Integer.decode(entry.get("outputenabled:"));
                        mState.outputs.add(output);
                    }
                }
            }
        }
        runOnUiThread(mOnPlayerStateUpdate);
    }

    private final OnCheckedChangeListener mOnOutputCheckedChange = new OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton b, boolean checked) {
            MpdRequest request = new MpdRequest((checked ? "enableoutput " : "disableoutput ") + b.getTag());
            mMpdThread.post(request);
            b.setEnabled(false);
        }
    };

    private void onPlayerStateUpdate() {
        synchronized (mState) {
            if (mState.state == State.playing) {
                mStationView.setText(mState.station);
                mProgramView.setText(mState.program);
                mAuthorView.setText(mState.author);
            } else {
                mStationView.setText("");
                mProgramView.setText(R.string.not_playing);
                mAuthorView.setText("");
            }
            if (mState.state == State.error) {
                mPlayButton.setEnabled(false);
                mStopButton.setEnabled(false);
                mVolumeBar.setEnabled(false);
                mVolumeBar.setProgress(50);
            } else {
                mPlayButton.setEnabled(mState.state != State.playingInitiated);
                mStopButton.setEnabled(mState.state == State.playing);
                mVolumeBar.setEnabled(true);
                if (mState.volume >= 0)
                    mVolumeBar.setProgress(mState.volume);
            }
            List<Preferences.AudioOutput> outputs = new ArrayList<>();
            for (Preferences.AudioOutput output : mState.outputs)
                if (mPreferences.isOutputVisible(output))
                    outputs.add(output);
            while (mOutputs.getChildCount() < outputs.size())
                mOutputs.addView(new ToggleButton(this));
            while (mOutputs.getChildCount() > outputs.size())
                mOutputs.removeViewAt(0);
            for (int i = 0; i < outputs.size(); ++i) {
                ToggleButton b = (ToggleButton) mOutputs.getChildAt(i);
                Preferences.AudioOutput output = outputs.get(i);
                b.setOnCheckedChangeListener(null);
                b.setTextOn(output.name);
                b.setTextOff(output.name);
                b.setTag(output.id);
                b.setChecked(output.enabled != 0);
                b.setEnabled(mState.state != State.error);
                b.setOnCheckedChangeListener(mOnOutputCheckedChange);
            }
        }
        mOutputs.setVisibility(View.GONE);
        mOutputs.setVisibility(View.VISIBLE);
    }
    private final Runnable mOnPlayerStateUpdate = () -> {
        try {
            onPlayerStateUpdate();
        } catch (Exception ignored) {
        }
    };


    private void onTimer() {
        if(mStatusMessage != null && mStatusMessage.isPersistent())
            mStatusMessage.show();

        long ms = getSleep();
        if (ms < 0)
            mRemainingSleepView.setVisibility(View.INVISIBLE);
        else {
            long s = ms / 1000, m = s / 60, h = m / 60;
            s %= 60;
            m %= 60;
            String time = String.format(Locale.US, "%d:%02d:%02d", h, m, s);
            mRemainingSleepView.setText(getString(R.string.message_remaining_sleep, time));
            mRemainingSleepView.setVisibility(View.VISIBLE);
        }
    }

    private void play(String playlist) {
        setStatusMessage(getString(R.string.msg_loading_playlist), LENGTH_PERSISTENT);
        mMpdThread.post(new MpdRequest("clear"));
        mMpdThread.post(new MpdRequest("load \"" + playlist + "\""));
        mMpdThread.post(new MpdRequest("play", new MpdRequest.OnDoneListener() {
            @Override
            void onMpdRequestDone(final MpdRequest request) {
                runOnUiThread(() -> {
                    clearStatusMessage();
                    if(request.getError() != null)
                        setStatusMessage(request.getError(), LENGTH_LONG);
                });

            }
        }));
    }

    private void stop() {
        setSleep(-1);
        mMpdThread.post(new MpdRequest("stop", new MpdRequest.OnDoneListener() {
            @Override
            void onMpdRequestDone(MpdRequest request) {
                mState.state = State.stopInitiated;
                runOnUiThread(mOnPlayerStateUpdate);
            }
        }));
    }

    private void setVolume(int percent) {
        String command = "setvol " + percent;
        mMpdThread.post(new MpdRequest(command));
    }

    private long getSleep() {
        long sleepTimeMs = mPreferences.getSleepTimeMs();
        if (sleepTimeMs < 0)
            return -1;
        return sleepTimeMs - System.currentTimeMillis();
    }

    private void setSleep(int ms) {
        long sleepTimeMs = -1L;
        if (ms > 0) {
            sleepTimeMs = System.currentTimeMillis() + ms;
            Alarm.schedule(this, sleepTimeMs);
        } else {
            sleepTimeMs = -1L;
            Alarm.cancel(this);
        }
        mPreferences.setSleepTimeMs(sleepTimeMs);
    }

    private void pickPlaylist(MpdRequest.Result r) {
        final ArrayList<String> playlists = new ArrayList<String>();
        String pattern = mPreferences.getPlaylistPattern();
        if (r != null) {
            for (HashMap<String, String> entry : r) {
                String name = entry.get("playlist:");
                if (name != null && name.matches(pattern))
                    playlists.add(name);
            }
        }
        if (playlists.isEmpty()) {
            Toast.makeText(this, getString(R.string.message_playlist_no_match, pattern), LENGTH_LONG).show();
            return;
        }
        String prefix = getLongestCommonPrefix(playlists);
        int pos = prefix.lastIndexOf('/');
        if(pos >= 0 && pos < prefix.length() - 1)
            prefix = prefix.substring(0, pos + 1);
        TreePicker.Node root = TreePicker.MakeTree(playlists, prefix);
        TreePicker.Node.Visitor visitor = new TreePicker.Node.Visitor() {
            @Override
            public void visit(TreePicker.Node node) {
                // sort children
                Collections.sort(node.children, new Comparator<TreePicker.Node>() {
                    @Override
                    public int compare(TreePicker.Node lhs, TreePicker.Node rhs) {
                        return lhs.name.compareTo(rhs.name);
                    }
                });
                // format name
                String s = node.name;
                int pos = s.lastIndexOf('.');
                if(pos > 0)
                    s = s.substring(0, pos);
                s = s.replaceFirst("^[0-9]+_", "");
                node.name = s;
                if(node.value == null)
                    node.name += "…";
            }
        };
        root.visit(visitor);
        TreePicker.Builder b = new TreePicker.Builder(this);
        b.setTitle(R.string.play_button_text);
        b.setRoot(root, node -> play(node.value));
        b.setNegativeButton(android.R.string.cancel, null);
        b.create().show();
    }

    private void pickSleep() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        View view = inflate(this, R.layout.sleep_picker, null);
        final EditText editText = (EditText) view.findViewById(R.id.edit_sleep_duration);
        OnClickListener onShortcutClick = new OnClickListener() {
            @Override
            public void onClick(View view) {
                if (view.getTag() != null && view.getTag().equals("clear")) {
                    editText.setText("");
                } else {
                    CharSequence s = ((TextView) view).getText();
                    editText.setText(s);
                    editText.setSelection(s.length());
                }
            }
        };
        TableLayout shortcuts = view.findViewById(R.id.sleep_shortcuts);
        for (int row = 0; row < shortcuts.getChildCount(); ++row) {
            TableRow tr = (TableRow) shortcuts.getChildAt(row);
            for (int col = 0; col < tr.getChildCount(); ++col) {
                View v = tr.getChildAt(col);
                v.setOnClickListener(onShortcutClick);
            }
        }
        long sleep = getSleep() / 60000;
        if (sleep > 0) {
            editText.setText(String.format(Locale.US, "%d:%02d", sleep / 60, sleep % 60));
        }
        b.setTitle(R.string.pick_sleep_title);
        b.setView(view);
        b.setPositiveButton(android.R.string.ok, (dialogInterface, i) -> {
            String value = editText.getText().toString();
            int sleep1 = -1;
            if (value.isEmpty())
                setSleep(sleep1);
            else try {
                int pos = value.indexOf(':');
                if (pos >= 0)
                    sleep1 = 60 * Integer.parseInt(value.substring(0, pos)) + Integer.parseInt(value.substring(pos + 1));
                else
                    sleep1 = Integer.parseInt(value);
                setSleep(sleep1 * 60000);
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, formatExceptionMessage(e), LENGTH_LONG).show();
            }
        });
        b.setNegativeButton(android.R.string.cancel, null);
        b.create().show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        mPreferences = Preferences.getInstance(this);

        mBackgroundThread = new BackgroundThread();
        mMpdThread = new MpdThread(this);
        mWsThread = new WsThread(this);

        mStationView = findViewById(R.id.station);
        mProgramView = findViewById(R.id.program);
        mAuthorView = findViewById(R.id.author);

        mPlayButton = findViewById(R.id.playButton);
        mPlayButton.setOnClickListener(view -> mMpdThread.post(new MpdRequest("listplaylists", new MpdRequest.OnDoneListener() {
            @Override
            void onMpdRequestDone(final MpdRequest request) {
                runOnUiThread(() -> pickPlaylist(request.getResult()));
            }
        })));

        mRemainingSleepView = findViewById(R.id.remaining_sleep);
        mStopButton = findViewById(R.id.stopButton);
        mStopButton.setOnClickListener(view -> stop());

        mVolumeBar = findViewById(R.id.volume_bar);
        mVolumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int i, boolean fromUser) {
                if (fromUser) setVolume(i);
            }

            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        mOutputs = findViewById(R.id.outputs);
        mUIThreadHandler = new Handler();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_item_settings:
                mPreferences.edit(this);
                break;
            case R.id.menu_item_sleep:
                pickSleep();
                break;
            case R.id.menu_item_quit:
                finish();
                break;
            default:
                return super.onOptionsItemSelected(item);
        }
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        mIsActive = true;
        mUIThreadHandler.post(new Runnable() {
            public void run() {
                onTimer();
                if (mIsActive) {
                    mUIThreadHandler.postDelayed(this, mUIUpdateDelayMs);
                }
            }
        });
        if (mPreferences.initRequired())
            mPreferences.edit(this);
        else {
            establishServerConnections();
        }}

    @Override
    protected void onPause() {
        clearStatusMessage();
        shutdownServerConnections();
        mIsActive = false;
        super.onPause();
    }

    @Override
    protected void finalize() throws Throwable {
        shutdownServerConnections();
        mIsActive = false;
        super.finalize();
    }

    private void establishServerConnections() {
        try {
            if (mConnection != null && mConnection.isConnected())
                return;

            final String host = mPreferences.getServerName();
            final int port = mPreferences.getServerPort();
            final int wsport = mPreferences.getServerWsPort();
            final int timeoutMs = mPreferences.getServerTimeoutMs();
            setStatusMessage(getString(R.string.msg_connecting, host, port), LENGTH_PERSISTENT);
            Callable<SocketConnection> connect = () -> {
                SocketConnection c = new SocketConnection(host, port);
                if (!c.waitForRead(timeoutMs))
                    throw new MpdRequest.ProtocolErrorException("MPD connection timeout");
                String s = c.readLine();
                if (!s.startsWith("OK "))
                    throw new MpdRequest.ProtocolErrorException(getString(R.string.msg_unexpected_server_response));
                return c;
            };
            try {
                mConnection = runInBackground(connect);
            } catch (ConnectException e1) {
                try {
                    if (!isWifiEnabled()) {
                        if (checkEnableWifiState()) {
                            restart();
                        } else {
                            throw e1;
                        }
                    }
                } catch (Exception e2) {
                    mConnection = null;
                    onError(e2);
                }
            }
            if (mConnection != null) {
                mState.state = State.idle;
                onNotification(getString(R.string.message_mpd_connected, host, port));
            } else {
                mState.state = State.error;
                throw new Exception(getString(R.string.message_mpd_fail, host, port));
            }
            mServerTimeoutMs = timeoutMs;
            for (String command : new String[]{
                    "crossfade " + (mPreferences.getCrossFadeDurationMs() / 1000),
                    "random " + (mPreferences.getShuffle() ? "1" : "0"),
                    "repeat " + (mPreferences.getRepeat() ? "1" : "0"),
            })
                mMpdThread.post(new MpdRequest(command));
            mMpdThread.setTimeoutMs(timeoutMs);
            mMpdThread.start(mConnection);
            mWsThread.start(host, wsport);
        } catch(Exception e) {
            onError(e);
        }
    }

    private boolean isWifiEnabled() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        return wm != null && wm.isWifiEnabled();
    }

    private boolean checkEnableWifiState() throws Exception {
        boolean didEnable = false;
        Preferences.WifiOptions enableWifi = mPreferences.getEnableWifi();
        if (enableWifi == Preferences.WifiOptions.enable) {
            setStatusMessage(getString(R.string.msg_enabling_wifi), LENGTH_PERSISTENT);
            didEnable = runInBackground(new Callable<Boolean>() {
                @Override
                public Boolean call() throws Exception {
                    return enableWifi();
                }
            });
            clearStatusMessage();
        } else if(enableWifi == Preferences.WifiOptions.ask) {
            AlertDialog.Builder b = new AlertDialog.Builder(this);
            b.setTitle(R.string.dialog_wifi_title);
            b.setMessage(R.string.dialog_wifi_message);
            b.setPositiveButton(R.string.dialog_wifi_button_autoenable, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    mPreferences.setEnableWifi(Preferences.WifiOptions.enable);
                    restart();
                }
            });
            b.setNeutralButton(R.string.dialog_wifi_button_opensettings, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                }
            });
            b.setNegativeButton(R.string.dialog_wifi_button_cancel, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    mPreferences.setEnableWifi(Preferences.WifiOptions.leave);
                }
            });
            b.setCancelable(true).create().show();
        }
        return didEnable;
    }

    private void restart() {
        finish();
        startActivity(new Intent(MainActivity.this, MainActivity.class));
    }

    private boolean enableWifi() {
        boolean isConnected = false;
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if(wm != null) {
            wm.setWifiEnabled(true);
            int t = mPreferences.getWifiTimeoutMs();
            int dt = 50;
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            while (cm != null && !isConnected && t > 0) {
                t -= dt;
                SystemClock.sleep(dt);
                isConnected = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI).isConnected();
            }
        }
        return isConnected;
    }

    private void shutdownServerConnections() {
        try {
            mWsThread.stop();
            mMpdThread.stop();
            if(mConnection != null) {
                mConnection.close();
                mConnection = null;
            }
        } catch(Exception e) {
            onError(e);
        }
    }

    private static String getLongestCommonPrefix(Collection<String> strings) {
        String prefix = "";
        Iterator<String> i = strings.iterator();
        if(i.hasNext())
            prefix = i.next();
        while(i.hasNext())
            prefix = getLongestCommonPrefix(prefix, i.next());
        return prefix;
    }

    private static String getLongestCommonPrefix(String s1, String s2) {
        int i = 0;
        for(; i < s1.length() && i < s2.length(); ++i)
            if(s1.charAt(i) != s2.charAt(i))
                return s1.substring(0, i);
        return s1.substring(0, i);
    }

    private static String cleanupString(String s) {
        while (s.endsWith(",") || s.endsWith(";") || s.endsWith(" ")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
