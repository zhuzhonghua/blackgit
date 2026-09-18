package com.blackcli.protocol;

import com.black.Log;

import java.util.HashMap;
import java.util.Map;

public class ProtocolRegistry {
    private static final Map<String, Protocol> protocols = new HashMap<>();
    
    public static void register(Protocol protocol) {
        protocols.put(protocol.name(), protocol);
        Log.logger.info("Registered protocol: {}", protocol.name());
    }
    
    public static Protocol get(String name) {
        return protocols.get(name);
    }

    static {
        register(new ListProtocol());
        register(new FetchProtocol());
        register(new PushProtocol());
    }
}