package com.blackgit;


import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.DefaultConfiguration;

import java.io.File;
import java.net.URI;
import java.net.URL;

public class Log {
    static {
        Log.configureLogging();
    }

    public static final Logger logger = LogManager.getLogger(Log.class);
    public static final Logger clj = LogManager.getLogger("[Clj]");
    public static final Logger net = LogManager.getLogger("[Net]");

    public static void die(int n) {
        System.exit(n);
    }

    /**
     * Makes sure a debug-level log4j2 configuration is in place no matter how
     * the process is started. When run from an IDE (or a classpath without the
     * project's {@code script/} resources), log4j2 would otherwise fall back to
     * its built-in configuration which only logs ERROR, so nothing shows up.
     */
    private static void configureLogging() {
        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            if (!(ctx.getConfiguration() instanceof DefaultConfiguration)) {
                return;
            }
            URI uri = findConfig();
            if (uri != null) {
                ctx.setConfigLocation(uri);
                System.err.println("[blackgit] using log4j2 config " + uri);
            } else {
                Configurator.setRootLevel(Level.DEBUG);
            }
        } catch (Exception ignored) {
            // logging setup is best-effort; never prevent startup
            try {
                Configurator.setRootLevel(Level.DEBUG);
            } catch (Exception ignoredToo) {
                // ignore
            }
        }
    }

    private static URI findConfig() {
        try {
            URL cls = Log.class.getClassLoader().getResource("log4j2.properties");
            if (cls != null) {
                return cls.toURI();
            }
            String[] candidates = {
                    "script/log4j2.properties",
                    "resources/log4j2.properties",
                    "log4j2.properties"
            };
            File cwd = new File("").getAbsoluteFile();
            for (String candidate : candidates) {
                File f = new File(cwd, candidate);
                if (f.isFile()) {
                    return f.toURI();
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }
}