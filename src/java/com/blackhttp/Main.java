package com.blackhttp;

import java.io.File;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Config config = parse(args);
        Server server = new Server(config);
        server.start();
        System.out.println("blackgit listening on port " + config.port
                + (config.readOnly ? " (read-only)" : "")
                + (config.tls ? " with TLS" : ""));
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        Thread.currentThread().join();
    }

    private static Config parse(String[] args) throws IllegalArgumentException {
        Config config = new Config();
        int i = 0;
        while (i < args.length) {
            String arg = args[i];
            switch (arg) {
                case "--port" -> config.port = Integer.parseInt(require(arg, value(args, ++i)));
                case "--root" -> config.repoBase = new File(require(arg, value(args, ++i)));
                case "--keystore" -> config.keystore = require(arg, value(args, ++i));
                case "--keystore-password" -> config.storePass = value(args, ++i).toCharArray();
                case "--key-password" -> config.keyPass = value(args, ++i).toCharArray();
                case "--spool-memory" ->
                        config.spoolMemoryLimit = (int) parseBytes(require(arg, value(args, ++i)));
                case "--http-threads" -> config.httpThreads = Integer.parseInt(require(arg, value(args, ++i)));
                case "--read-only" -> config.readOnly = true;
                case "--tls" -> config.tls = true;
                case "--help", "-h" -> {
                    usage();
                    System.exit(0);
                }
                default -> throw new IllegalArgumentException("unknown argument: " + arg);
            }
            i++;
        }
        return config;
    }

    private static String value(String[] args, int i) {
        return i < args.length ? args[i] : null;
    }

    private static String require(String arg, String value) {
        if (value == null) {
            throw new IllegalArgumentException("missing value for " + arg);
        }
        return value;
    }

    private static long parseBytes(String s) {
        s = s.trim();
        char suffix = s.isEmpty() ? 'b' : s.charAt(s.length() - 1);
        long mult = 1;
        switch (Character.toLowerCase(suffix)) {
            case 'k', 'K' -> mult = 1024;
            case 'm', 'M' -> mult = 1024 * 1024;
            case 'g', 'G' -> mult = 1024 * 1024 * 1024;
            default -> { }
        }
        String num = (mult == 1 ? s : s.substring(0, s.length() - 1)).trim();
        return Long.parseLong(num) * mult;
    }

    private static void usage() {
        System.out.println("""
                usage: blackgit [options]
                  --port <n>              listen port (default 8080)
                  --root <dir>            base dir for repositories resolved from the URL
                                          (default $HOME; the URL /xxx.git maps to <root>/xxx)
                  --read-only             reject pushes
                  --tls                   enable HTTPS
                  --keystore <path>       PKCS12/JKS keystore for TLS
                  --keystore-password <p> keystore password
                  --key-password <p>      key password
                  --spool-memory <bytes>  in-memory buffer before spooling to disk (default 1M)
                  --http-threads <n>      worker threads for git processing
                """);
    }
}