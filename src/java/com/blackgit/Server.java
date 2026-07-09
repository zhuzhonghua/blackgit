package com.blackgit;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import org.apache.commons.lang3.exception.ExceptionUtils;

public class Server {

    private int port;
    private Selector selector;
    private ServerSocketChannel srvchannel;

    public Server(int p) {
        port = p;

        init();
    }

    private void init() {
        try {
            selector = Selector.open();
            srvchannel = ServerSocketChannel.open();
            srvchannel.configureBlocking(false);
            srvchannel.socket().bind(new InetSocketAddress(port));
            srvchannel.register(selector, SelectionKey.OP_ACCEPT);

            Log.logger.info("Server listen on port {}", port);
            handleNetEvents();
        } catch (Exception e) {
            Log.logger.error(ExceptionUtils.getStackTrace(e));
            Log.die(2);
        }
    }

    private void handleNetEvents() throws Exception {
        while (true) {
            selector.selectNow();

            Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
            while (iter.hasNext()) {
                SelectionKey key = iter.next();
                iter.remove();
                if (key.isAcceptable()) {
                    handleAccept();
                } else {
                    SocketClient client = (SocketClient) key.attachment();
                    try {
                        if (key.isReadable()) {
                            while (client.read());
                        }
                        if (key.isWritable()) {
                            client.handleWrite(key);
                        }
                    } catch (Exception e) {
                        Log.logger.info("select read write error {}", client.addr);
                        Log.logger.info(ExceptionUtils.getStackTrace(e));
                        client.error(key);
                    }
                }
            }

            Thread.sleep(100);
        }
    }

    private void handleAccept() {
        try {
            SocketChannel channel = srvchannel.accept();
            channel.configureBlocking(false);
            SocketAddress addr = channel.getRemoteAddress();
            SocketClient client = new SocketClient(selector, channel, addr);
            channel.register(selector, SelectionKey.OP_READ, client);
            Log.logger.info("new client incoming {}", addr);
        } catch (IOException e) {
            Log.logger.info("Accept client error");
        }
    }
}
