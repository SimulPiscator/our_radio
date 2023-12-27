package org.simulpiscator.our_radio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;

class SocketConnection {
    static final Charset UTF8 = StandardCharsets.UTF_8;
    static final Charset Latin1 = StandardCharsets.ISO_8859_1;

    private final SocketChannel mSocket;
    private final Selector mReadSelector;
    private final SelectionKey mReadKey;
    private final Selector mWriteSelector;
    private final SelectionKey mWriteKey;
    private final ByteBuffer mBuffer;
    static private final byte EOL = '\n';

    public SocketConnection(String host, int port) throws IOException {
        SocketAddress address = new InetSocketAddress(host, port);
        mSocket = SocketChannel.open(address);
        mSocket.configureBlocking(false);
        mReadSelector = Selector.open();
        mReadKey = mSocket.register(mReadSelector, OP_READ);
        mWriteSelector = Selector.open();
        mWriteKey = mSocket.register(mWriteSelector, OP_WRITE);
        mBuffer = ByteBuffer.allocate(65536);
        mBuffer.flip();
    }

    public void close() throws IOException {
        mReadSelector.close();
        mWriteSelector.close();
        mSocket.close();
    }

    public boolean isConnected() {
        return mSocket.isConnected();
    }

    public boolean canRead() throws IOException {
        return waitForRead(-1);
    }

    public boolean waitForRead() throws IOException {
        return waitForRead(0);
    }

    public boolean waitForRead(int timeoutMs) throws IOException {
        if (mBuffer.hasRemaining())
            return true;
        mReadSelector.selectedKeys().clear();
        if (timeoutMs >= 0)
            mReadSelector.select(timeoutMs);
        else
            mReadSelector.selectNow();
        if (mReadSelector.selectedKeys().contains(mReadKey))
            return mReadKey.isValid() && mReadKey.isReadable();
        return false;
    }

    public boolean canWrite() throws IOException {
        return waitForWrite(-1);
    }

    public boolean waitForWrite() throws IOException {
        return waitForWrite(0);
    }

    public boolean waitForWrite(int timeoutMs) throws IOException {
        mWriteSelector.selectedKeys().clear();
        if (timeoutMs >= 0)
            mWriteSelector.select(timeoutMs);
        else
            mWriteSelector.selectNow();
        if (mWriteSelector.selectedKeys().contains(mWriteKey))
            return mWriteKey.isValid() && mWriteKey.isWritable();
        return false;
    }

    public String readLine() throws IOException {
        return readLine(Latin1);
    }

    public String readLine(Charset charset) throws IOException {
        StringBuilder sb = new StringBuilder();

        // assuming valid data between buffer's position and limit
        boolean abort = false;
        while((mBuffer.hasRemaining() || mSocket.isConnected()) && !abort) {
            byte[] buf = mBuffer.array();
            int i = mBuffer.position();
            while(i < mBuffer.limit() && buf[i] != EOL)
                ++i;
            sb.append(new String(buf, mBuffer.position(), i - mBuffer.position(), charset));
            if(i < mBuffer.limit()) {
                mBuffer.position(i + 1);
                break;
            }
            mBuffer.clear();
            int read = 0;
            while(read == 0 && !abort) {
                mReadSelector.selectedKeys().clear();
                mReadSelector.select();
                if (mReadSelector.selectedKeys().contains(mReadKey))
                    abort = !mReadKey.isValid() && !mReadKey.isReadable();
                else
                    abort = true;
                read = mSocket.read(mBuffer);
            }
            mBuffer.flip();
        }
        return sb.toString();
    }

    public void writeLine(String s, Charset charset) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap((s + '\n').getBytes(charset));
        while(buf.hasRemaining()) {
            waitForWrite();
            mSocket.write(buf);
        }
    }

    public void writeLine(String s) throws IOException {
        writeLine(s, Latin1);
    }
}
