package com.blackhttp;

import com.black.Log;
import com.blackhttp.githttp.RepoResolver;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;

import java.io.File;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

final class Server {
    private final Config config;
    private final RepoResolver repoResolver;
    private final EventLoopGroup boss;
    private final EventLoopGroup worker;
    private final SslContext sslContext;
    private final EventExecutorGroup httpExecutor;
    private Channel channel;

    Server(Config config) throws Exception {
        this.config = config;
        this.repoResolver = new RepoResolver(new File(config.repoBase, "").getAbsoluteFile());
        this.boss = new NioEventLoopGroup(1, daemonThreads("blackgit-boss"));
        this.worker = new NioEventLoopGroup(0, daemonThreads("blackgit-netty"));
        this.httpExecutor = new DefaultEventExecutorGroup(
                config.httpThreads > 0 ? config.httpThreads
                        : Runtime.getRuntime().availableProcessors(),
                daemonThreads("blackgit-http"));
        this.sslContext = config.tls ? HttpSslContextFactory.create(config) : null;
    }

    void start() throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap();
        b.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new LifecycleLogger());
                        ch.pipeline().addLast(new ProtocolDetector(Server.this));
                    }
                })
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true);
        this.channel = b.bind(config.port).sync().channel();
        Log.logger.info("server listening on {} repo base {}",
                channel.localAddress(), config.repoBase);
    }

    void stop() {
        Log.logger.info("server shutting down");
        if (channel != null) {
            channel.close().syncUninterruptibly();
            channel = null;
        }
        httpExecutor.shutdownGracefully();
        boss.shutdownGracefully();
        worker.shutdownGracefully();
    }

    boolean sslEnabled() {
        return sslContext != null;
    }

    SslContext sslContext() {
        return sslContext;
    }

    RepoResolver repoResolver() {
        return repoResolver;
    }

    Config config() {
        return config;
    }

    EventExecutorGroup httpExecutor() {
        return httpExecutor;
    }

    private static final class LifecycleLogger extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            Log.net.debug("connection established from {}", ctx.channel().remoteAddress());
            ctx.fireChannelActive();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            Log.net.debug("connection closed from {}", ctx.channel().remoteAddress());
            ctx.fireChannelInactive();
        }
    }

    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}