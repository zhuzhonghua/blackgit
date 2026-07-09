package com.blackgit;


import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Log {
    public static final Logger logger = LogManager.getLogger(Log.class);
    public static final Logger clj = LogManager.getLogger("[Clj]");
    public static final Logger net = LogManager.getLogger("[Net]");

    public static void die(int n) {
        System.exit(n);
    }
}
