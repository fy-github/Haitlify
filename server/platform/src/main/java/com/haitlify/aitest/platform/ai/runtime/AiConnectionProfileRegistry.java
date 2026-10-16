package com.haitlify.aitest.platform.ai.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.haitlify.aitest.platform.ai.runtime.AiRuntimeModels.AiConnection;
import com.haitlify.aitest.platform.ai.runtime.AiRuntimeModels.AiModelProfile;

@Service
public class AiConnectionProfileRegistry {
    private final Map<String, AiConnection> connections = new ConcurrentHashMap<>();
    private final Map<String, AiModelProfile> profiles = new ConcurrentHashMap<>();

    public void register(AiConnection connection) { connections.put(connection.id(), connection); }
    public void register(AiModelProfile profile) {
        if (!connections.containsKey(profile.connectionId())) throw new IllegalArgumentException("connection not found: " + profile.connectionId());
        profiles.put(profile.id(), profile);
    }
    public AiConnection connection(String id) { return required(connections.get(id), "connection", id); }
    public AiModelProfile profile(String id) { return required(profiles.get(id), "profile", id); }
    private <T> T required(T value, String kind, String id) { if (value == null) throw new IllegalArgumentException(kind + " not found: " + id); return value; }
}
