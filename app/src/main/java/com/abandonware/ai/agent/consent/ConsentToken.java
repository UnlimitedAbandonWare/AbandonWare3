package com.abandonware.ai.agent.consent;

import com.abandonware.ai.agent.tool.ToolScope;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class ConsentToken {
    private final String id;
    private final String identity;
    private final Set<ToolScope> scopes;
    private final long expiresAtEpochMs;

    public ConsentToken(String identity, Set<ToolScope> scopes, long ttlSeconds) {
        this.id = UUID.randomUUID().toString();
        this.identity = identity;
        this.scopes = Set.copyOf(scopes);
        this.expiresAtEpochMs = Instant.now().toEpochMilli() + Math.max(0, ttlSeconds) * 1000L;
    }

    public String id(){ return id; }
    public String identity(){ return identity; }
    public Set<ToolScope> scopes(){ return scopes; }
    public long expiresAtEpochMs(){ return expiresAtEpochMs; }
    public boolean expired(){ return Instant.now().toEpochMilli() > expiresAtEpochMs; }
}