package com.jason.demo.demo2.agentscope.state;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.State;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PathSafeAgentStateStoreTest {

    public static final class DummyState implements State {
        private String value;

        public DummyState() {
        }

        public DummyState(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    @Test
    void roundTripsSessionIdContainingSlash() {
        AgentStateStore memory = new InMemoryAgentStateStore();
        PathSafeAgentStateStore store = new PathSafeAgentStateStore(memory);

        String userId = "u1";
        String sessionId = "sandbox/session/s-015";
        String key = "agent";
        store.save(userId, sessionId, key, new DummyState("ok"));

        Optional<DummyState> loaded = store.get(userId, sessionId, key, DummyState.class);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getValue()).isEqualTo("ok");

        assertThat(store.exists(userId, sessionId)).isTrue();
        Set<String> ids = store.listSessionIds(userId);
        assertThat(ids).contains(sessionId);

        store.delete(userId, sessionId);
        assertThat(store.exists(userId, sessionId)).isFalse();
    }

    @Test
    void encodeDecodeAreReversible() {
        String raw = "sandbox/session/abc";
        String encoded = PathSafeAgentStateStore.encode(raw);
        assertThat(encoded).doesNotContain("/");
        assertThat(PathSafeAgentStateStore.decode(encoded)).isEqualTo(raw);
    }
}
