package com.smarthttp;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

import javax.net.ssl.KeyManagerFactory;
import java.io.FileInputStream;
import java.security.KeyStore;

final class HttpSslContextFactory {
    private HttpSslContextFactory() {
    }

    static SslContext create(Config config) throws Exception {
        if (config.keystore == null) {
            throw new IllegalStateException("--tls requires --keystore");
        }
        KeyStore ks = KeyStore.getInstance(config.storeType);
        try (FileInputStream in = new FileInputStream(config.keystore)) {
            ks.load(in, config.storePass);
        }
        KeyManagerFactory kmf =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, config.keyPass);
        return SslContextBuilder.forServer(kmf).build();
    }
}