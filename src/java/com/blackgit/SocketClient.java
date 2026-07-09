package com.blackgit;

import com.google.protobuf.Message;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;

public class SocketClient {

    private Selector selector;
    private SocketChannel channel;
    private ByteBuffer lenbuf;
    private ByteBuffer bodybuf;
    private ConcurrentLinkedQueue<ByteBuffer> outq = new ConcurrentLinkedQueue<>();
    public SocketAddress addr;
    private StringBuilder lineBuffer = new StringBuilder();
    private static final int MAX_BUFFER_SIZE = 65535;
    private LineProcesser processer = new LineProcesser(this);

    public SocketClient(Selector sel, SocketChannel c, SocketAddress a) {
        selector = sel;
        channel = c;
        addr = a;

        lenbuf = ByteBuffer.allocate(4);
    }

    public boolean read() throws Exception {
        if (bodybuf != null) {
            int count = channel.read(bodybuf);
            if (count < 0) {
                throw new Exception("readbody error " + addr);
            }

            if (!bodybuf.hasRemaining()) {
                bodybuf.flip();
                OPProcesser.process(this, bodybuf);
                bodybuf = null;
                //String recv = new String(bodybuf.array(), 0, bodybuf.limit(), StandardCharsets.UTF_8);
                //processer.processLine(recv);
            }
            return count > 0;
        } else {
            int count = channel.read(lenbuf);
            if (count < 0) {
                throw new Exception("readlen error " + addr);
            }
            if (!lenbuf.hasRemaining()) {
                lenbuf.flip();
                int len = lenbuf.getInt();
                if (len >= 65535 || len <= 0) {
                    throw new Exception("illegal len " + len +" addr "+ addr);
                }
                lenbuf.clear();
                bodybuf = ByteBuffer.allocate(len);
                Log.net.debug("to read {} bytes", len);
            }
            return count > 0;
        }
    }

    public void write(Message message) throws IOException {
        write(message.toByteArray());
    }

    public void write(String data) throws IOException {
        write(data.getBytes(StandardCharsets.UTF_8));
    }

    public void write(byte[] arr) throws IOException {
        channel.register(selector, SelectionKey.OP_WRITE | SelectionKey.OP_READ, this);
        ByteBuffer buf = ByteBuffer.allocate(arr.length+4);
        buf.putInt(arr.length);
        buf.put(arr);
        outq.offer(buf.flip());
    }

    public void handleWrite(SelectionKey key) throws IOException {
        ByteBuffer by = outq.peek();
        while (by != null) {
            int count = channel.write(by);
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
        lenbuf.clear();
    }
}
