package com.blackgit;

import com.blackgit.protocol.Protocol;
import com.blackgit.protocol.ProtocolRegistry;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;

public class SocketClient {

    private Selector selector;
    private SocketChannel channel;
    private ByteBuffer headerbuf;
    private ByteBuffer bodybuf;
    private ConcurrentLinkedQueue<ByteBuffer> outq = new ConcurrentLinkedQueue<>();
    public SocketAddress addr;

    private int readState = 1;
    private int frameLength = 0;
    private ByteBuffer namebuf;
    private int bodyLength = 0;
    private String protocolName = null;
    private static final int MAX_BUFFER_SIZE = 65535;
    private static final int MAX_NAME_LENGTH = 256;
    private static final int MAX_FRAME_LENGTH = MAX_NAME_LENGTH + 1 + MAX_BUFFER_SIZE;

    public SocketClient(Selector sel, SocketChannel c, SocketAddress a) {
        selector = sel;
        channel = c;
        addr = a;
        headerbuf = ByteBuffer.allocate(4 + MAX_NAME_LENGTH + 1).order(ByteOrder.BIG_ENDIAN);
    }

    public boolean read() throws Exception {
        switch (readState) {
            case 1: return readFrameLength();
            case 2: return readName();
            case 3: return readBody();
            default: throw new Exception("internal err state=" + readState);
        }
    }

    private boolean readFrameLength() throws Exception {
        int count = channel.read(headerbuf);
        if (count < 0) {
            throw new Exception("read frame length error " + addr);
        }

        if (headerbuf.position() >= 4) {
            headerbuf.flip();
            frameLength = headerbuf.getInt();
            if (frameLength < 2 || frameLength > MAX_FRAME_LENGTH) {
                throw new Exception("illegal frame length " + frameLength + " addr " + addr);
            }

            namebuf = ByteBuffer.allocate(4 + MAX_NAME_LENGTH + 1);
            namebuf.put(headerbuf);
            headerbuf.clear();
            readState = 2;
            return true;
        }
        return count > 0;
    }

    private boolean readName() throws Exception {
        int count = channel.read(namebuf);
        if (count < 0) {
            throw new Exception("read name error " + addr);
        }

        int limit = namebuf.position();
        for (int i = 0; i < limit; i++) {
            if (namebuf.get(i) == '\n') {
                namebuf.flip();
                int nameLen = i;
                if (nameLen <= 0 || nameLen > MAX_NAME_LENGTH) {
                    throw new Exception("illegal protocol name length " + nameLen + " addr " + addr);
                }
                protocolName = new String(namebuf.array(), 0, nameLen).trim();
                Log.net.debug("Protocol: {}", protocolName);

                Protocol protocol = ProtocolRegistry.get(protocolName);
                if (protocol == null) {
                    throw new Exception("Unknown protocol: " + protocolName);
                }

                bodyLength = frameLength - nameLen - 1;
                if (bodyLength < 0 || bodyLength > MAX_BUFFER_SIZE) {
                    throw new Exception("illegal body length " + bodyLength + " addr " + addr);
                }
                namebuf.position(i + 1);
                bodybuf = ByteBuffer.allocate(bodyLength);
                byte[] prefix = new byte[namebuf.remaining()];
                namebuf.get(prefix);
                int avail = Math.min(bodybuf.remaining(), prefix.length);
                bodybuf.put(prefix, 0, avail);

                readState = 3;
                return true;
            }
        }
        if (limit > MAX_NAME_LENGTH) {
            throw new Exception("Protocol name too long " + addr);
        }
        return count > 0;
    }

    private boolean readBody() throws Exception {
        int count = 0;
        if (bodybuf.hasRemaining()) {
            count = channel.read(bodybuf);
            if (count < 0) {
                throw new Exception("readbody error " + addr);
            }
        }

        if (!bodybuf.hasRemaining()) {
            readState = 1;
            bodybuf.flip();
            Protocol protocol = ProtocolRegistry.get(protocolName);
            if (protocol != null) {
                protocol.handle(this, bodybuf);
            }
            bodybuf = null;
            protocolName = null;
            return true;
        }
        return count > 0;
    }

    public void write(String data) throws IOException {
        write(data.getBytes(StandardCharsets.UTF_8));
    }

    public void write(byte[] arr) throws IOException {
        channel.register(selector, SelectionKey.OP_WRITE | SelectionKey.OP_READ, this);
        ByteBuffer buf = ByteBuffer.allocate(arr.length + 4).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(arr.length);
        buf.put(arr);
        outq.offer(buf.flip());
    }

    public void handleWrite() throws IOException {
        ByteBuffer by = outq.peek();
        while (by != null) {
            channel.write(by);
            if (by.hasRemaining())
                break;

            outq.poll();
            by = outq.peek();
        }
        if (outq.isEmpty()) {
            channel.register(selector, SelectionKey.OP_READ, this);
        }
    }

    public void error(SelectionKey key) throws IOException {
        if (key != null) {
            key.attach(null);
            key.cancel();
        }
        channel.close();
        headerbuf.clear();
    }
}
