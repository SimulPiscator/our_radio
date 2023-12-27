package org.simulpiscator.our_radio;

import android.util.Log;
import java.net.URI;
import java.util.IdentityHashMap;
import java.util.Map;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.simulpiscator.our_radio.Notify;

class WsThread {
    private static final String TAG = "sp:wsthread";
    private volatile Thread mThread;
    private String mIpAddress;
    private int mPort;

    private final Object mLock = new Object();

    public interface Listener {
        void onWsError(Exception error);
        void onWsSubsystemChange(String subsystem);
    }
    private final Listener mListener;
    private volatile WebSocketClient mWsClient;

    protected void reportError(Exception error) {
        if(mListener != null) {
            mListener.onWsError(error);
        } else {
            Log.e(TAG, error.getClass() + ":" + error.getMessage());
        }
    }

    WsThread(Listener listener) {
        mListener = listener;
    }

    void start(String ip, int port) {
        mIpAddress = ip;
        mPort = port;
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
        if(mThread != null && mWsClient != null) {
            try {
                mWsClient.closeBlocking();
                mThread.join();
            } catch (InterruptedException e) {
                reportError(e);
            }
        }
    }

    private void ioLoop() {
        try {
            if (mListener != null)
                mListener.onWsSubsystemChange("");
            class WsClient extends WebSocketClient {

                final Notify mNotify = new Notify();

                public WsClient(URI serverUri, Map<String, String> httpHeaders) {
                    super(serverUri, httpHeaders);
                }

                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    Notify n = new Notify();
                    n.notify = new String[]{"player", "volume", "outputs"};
                    String json = n.toJson();
                    this.send(json);
                }

                @Override
                public void onMessage(String message) {
                    mNotify.fromJson(message);
                    if (mListener != null) {
                        for (String s : mNotify.notify) {
                            mListener.onWsSubsystemChange(s);
                        }
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                }

                @Override
                public void onError(Exception ex) {
                    reportError(ex);
                }
            }
            URI uri = new URI("ws://" + mIpAddress + ":" + mPort);
            IdentityHashMap<String, String> headers = new IdentityHashMap<>();
            headers.put("Host", "localhost:" + mPort);
            headers.put("Origin", "http://localhost:" + mPort);
            headers.put("Sec-WebSocket-Protocol", "notify");

            mWsClient = new WsClient(uri, headers);
            mWsClient.run();
        } catch (Exception e) {
            reportError(e);
        }
    }
}
