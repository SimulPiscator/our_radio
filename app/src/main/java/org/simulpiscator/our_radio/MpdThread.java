package org.simulpiscator.our_radio;

import android.util.Log;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SelectionKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static java.nio.channels.SelectionKey.OP_READ;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

class MpdThread {

    private static final String TAG = "sp:mpdthread";
    private volatile Thread mThread;
    private volatile boolean mTerminate = false;
    private int mTimeoutMs = 1000;
    private final Object mLock = new Object();
    private volatile SocketConnection mSocketConnection;
    private final ConcurrentLinkedQueue<MpdRequest> mRequests;
    public interface Listener {
        void onMpdError(Exception error);
    }
    private final Listener mListener;
    protected void reportError(Exception error) {
        if(mListener != null) {
            mListener.onMpdError(error);
        } else {
            Log.e(TAG, error.getClass() + ":" + error.getMessage());
        }
    }

    MpdThread(Listener listener) {
        mRequests = new ConcurrentLinkedQueue<>();
        mListener = listener;
    }

    void start(SocketConnection connection) {
        mSocketConnection = connection;
        mTerminate = false;
        mThread = new Thread(null, null, TAG) {
            @Override
            public void run() {
                try {
                    synchronized(mLock) {
                        mLock.notify();
                    }
                    ioLoop();
                } catch (Exception e) {
                    reportError(e);
                }
                synchronized (mLock) {
                    mLock.notify();
                }
            }
        };
        mThread.start();
        synchronized(mLock) {
            try {
                mLock.wait();
            } catch (InterruptedException e) {
                reportError(e);
            }
        }
    }

    void stop() {
        if(mThread != null) {
            mTerminate = true;
            try {
                synchronized (mRequests) {
                    mRequests.clear();
                    mRequests.notify();
                }
                mThread.join();
            } catch (InterruptedException e) {
                reportError(e);
            }
        }
    }

    int getTimeoutMs() {
        return mTimeoutMs;
    }

    void setTimeoutMs(int timeoutMs) {
        mTimeoutMs = timeoutMs;
    }

    private void ioLoop() {
        boolean done = false;
        try {
            while (!mTerminate) {
                synchronized (mRequests) {
                    mRequests.wait();
                }
                MpdRequest r = mRequests.poll();
                while (r != null) {
                    r.process(mSocketConnection, mTimeoutMs);
                    r = mRequests.poll();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    void post(MpdRequest r) {
        mRequests.add(r);
        synchronized (mRequests) {
            mRequests.notify();
        }
    }
}
