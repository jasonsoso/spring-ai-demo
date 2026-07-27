package com.jason.demo.demo2.agentscope.state;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 将含 {@code /} 的 sessionId（如 {@code sandbox/session/...}）编码后再交给底层 store。
 */
public final class PathSafeAgentStateStore implements AgentStateStore {

    private final AgentStateStore delegate;

    public PathSafeAgentStateStore(AgentStateStore delegate) {
        this.delegate = delegate;
    }

    static String encode(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static String decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return encoded;
        }
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    @Override
    public void save(String userId, String sessionId, String key, State state) {
        delegate.save(userId, encode(sessionId), key, state);
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> states) {
        delegate.save(userId, encode(sessionId), key, states);
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        return delegate.get(userId, encode(sessionId), key, type);
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> type) {
        return delegate.getList(userId, encode(sessionId), key, type);
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return delegate.exists(userId, encode(sessionId));
    }

    @Override
    public void delete(String userId, String sessionId) {
        delegate.delete(userId, encode(sessionId));
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        delegate.delete(userId, encode(sessionId), key);
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        return delegate.listSessionIds(userId).stream()
                .map(PathSafeAgentStateStore::decode)
                .collect(Collectors.toSet());
    }

    @Override
    public void close() {
        delegate.close();
    }
}
