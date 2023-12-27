package org.simulpiscator.our_radio;

import android.util.Log;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;

import static org.simulpiscator.our_radio.SocketConnection.UTF8;
import static org.simulpiscator.our_radio.SocketConnection.Latin1;

class MpdRequest {
    private static final String TAG = "sp:mpdrequest";

    static abstract class OnDoneListener {
        abstract void onMpdRequestDone(MpdRequest request);
    }

    static class Result extends ArrayList<HashMap<String, String>> {
    }

    private final String mCommand;
    private final OnDoneListener mListener;
    private volatile String mError;
    private Result mResult;

    MpdRequest(String command) {
        this(command, null);
    }

    MpdRequest(String command, OnDoneListener listener) {
        mCommand = command;
        mListener = listener;
    }

    String getCommand() { return mCommand; }
    String getError() { return mError; }
    Result getResult() { return mResult; }

    boolean process(SocketConnection connection, int timeoutMs) throws IOException {
        mResult = new MpdRequest.Result();
        synchronized (connection) {
            connection.writeLine(mCommand, Latin1);
            boolean done = false;
            while (!done) {
                if (!connection.waitForRead(timeoutMs))
                    throw new SocketTimeoutException();
                String line = connection.readLine(Latin1);
                if ("OK".equals(line)) {
                    done = true;
                } else if (line.startsWith("ACK ")) {
                    mError = line;
                    done = true;
                } else {
                    String[] fields = line.split("\\s+", 2);
                    if (fields.length > 0) {
                        String key = fields[0].toLowerCase();
                        if (mResult.isEmpty() || mResult.get(mResult.size() - 1).containsKey(key))
                            mResult.add(new HashMap<String, String>());
                        String value = "";
                        if (fields.length > 1)
                            value = fields[1];
                        byte[] bytes = value.getBytes(Latin1);
                        value = new String(bytes, detectCharset(bytes));
                        mResult.get(mResult.size() - 1).put(key, value);
                    }
                }
            }
        }
        if(mListener != null)
            mListener.onMpdRequestDone(this);
        return mError == null;
    }

    Charset detectCharset(byte[] bytes) {
        boolean maybeUTF8 = true;
        boolean lastByteIsMark = false;
        for (byte b: bytes) {
            if(b == (byte)0xc2 || b == (byte)0xc3)
                lastByteIsMark = true;
            else if(b < 0 && !lastByteIsMark)
                maybeUTF8 = false;
            else if(b > 0 && lastByteIsMark)
                lastByteIsMark = false;
        }
        if(maybeUTF8)
            return UTF8;
        return Latin1;
    }

    static class ProtocolErrorException extends Exception {
        ProtocolErrorException(String s) {
            super(s);
        }
    }
}
