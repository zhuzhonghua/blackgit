package com.smarthttp.blackgit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class BlackGitRegistry {
    private static final Map<String, BlackGitProtocol> protocols = new ConcurrentHashMap<>();

    private BlackGitRegistry() {
    }

    public static void register(BlackGitProtocol protocol) {
        protocols.put(protocol.name(), protocol);
    }

    public static void unregister(String name) {
        protocols.remove(name);
    }

    public static BlackGitProtocol get(String name) {
        return protocols.get(name);
    }

    public static Map<String, BlackGitProtocol> protocols() {
        return protocols;
    }
}