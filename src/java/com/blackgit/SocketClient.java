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
    private static final int MAX_BUFFER_SIZE = 65535;

    private int readState = 1;
    private String protocolName = null;
    private int bodyLength = 0;

    public SocketClient(Selector sel, SocketChannel c, SocketAddress a) {
        selector = sel;
        channel = c;
        addr = a;
        headerbuf = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN);
    }

    public boolean read() throws Exception {
        switch (readState) {
            case 1: return readProtocolName();
            case 2: return readBodyLength();
            case 3: return readBody();
            default: throw new Exception("internal err state=" + readState);
        }
    }

    private boolean readProtocolName() throws Exception {
        int count = channel.read(headerbuf);
        if (count < 0) {
            throw new Exception("read protocol name error " + addr);
        }
        
        int newlinePos = -1;
        for (int i = 0; i < headerbuf.position(); i++) {
            if (headerbuf.get(i) == '\n') {
                newlinePos = i;
                break;
            }
        }
        
        if (newlinePos >= 0) {
            headerbuf.flip();
            protocolName = new String(headerbuf.array(), 0, newlinePos+1).trim();
            headerbuf.position(newlinePos+1);

            Log.net.debug("Protocol: {}", protocolName);
            
            Protocol protocol = ProtocolRegistry.get(protocolName);
            if (protocol == null) {
                throw new Exception("Unknown protocol: " + protocolName);
            }
            
            readState = 2;
            headerbuf.compact();
        } else if (!headerbuf.hasRemaining()) {
            throw new Exception("Protocol name too long");
        }
        return count > 0;
    }

    private boolean readBodyLength() throws Exception {
        int count = channel.read(headerbuf);
        if (count < 0) {
            throw new Exception("read body length error " + addr);
        }
        if (headerbuf.position() >= 4) {
            headerbuf.flip();
            bodyLength = headerbuf.getInt();
            if (bodyLength > MAX_BUFFER_SIZE || bodyLength < 0) {
                throw new Exception("illegal body length " + bodyLength + " addr " + addr);
            }
            if (headerbuf.remaining() > bodyLength) {
                throw new Exception("too much data, illegal body length " + bodyLength + " addr " + addr);
            }
            readState = 3;
            bodybuf = ByteBuffer.allocate(bodyLength);
            bodybuf.put(headerbuf);
            headerbuf.clear();
            return true;
        }
        return count > 0;
    }

    private boolean readBody() throws Exception {
        int count = channel.read(bodybuf);
        if (count < 0) {
            throw new Exception("readbody error " + addr);
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
