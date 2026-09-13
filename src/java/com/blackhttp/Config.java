package com.blackhttp;

import java.io.File;

public final class Config {
    public int port = 8080;
    public boolean readOnly;
    /** Base directory for repositories resolved from the URL (default $HOME). */
    public File repoBase = new File(System.getProperty("user.home"));
    public boolean tls;
    public String keystore;
    public String storeType = "JKS";
    public char[] storePass = new char[0];
    public char[] keyPass = new char[0];
    /** Bytes buffered in memory before a request/response spools to disk. */
    public int spoolMemoryLimit = 1 * 1024 * 1024;
    /** Extra I/O threads for git CPU work; 0 means Netty default. */
    public int httpThreads;
}