package com.jason.demo.demo2.agentscope.diff;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceDiffServiceTest {

    @TempDir
    Path tempDir;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void resolveSnapshotId_readsRemoteIdFromSandboxState() {
        AgentStateStore store = mock(AgentStateStore.class);
        String json = """
                {"type":"docker","sessionId":"abc","snapshot":{"type":"remote","id":"snap-123"}}
                """;
        when(store.get(
                isNull(),
                eq("sandbox/session/plan-session-017"),
                eq("_sandbox_state"),
                eq(SandboxStateSlotView.class)))
                .thenReturn(Optional.of(new SandboxStateSlotView(json, false)));

        WorkspaceDiffService service = WorkspaceDiffService.forTest(
                tempDir.resolve("project"),
                mock(SandboxSnapshotSpec.class),
                store,
                JSON);

        assertThat(service.resolveSnapshotId("plan-session-017")).contains("snap-123");
    }

    @Test
    void resolveSnapshotId_emptyWhenMissingOrDeleted() {
        AgentStateStore store = mock(AgentStateStore.class);
        when(store.get(
                isNull(),
                eq("sandbox/session/missing"),
                eq("_sandbox_state"),
                eq(SandboxStateSlotView.class)))
                .thenReturn(Optional.empty());
        when(store.get(
                isNull(),
                eq("sandbox/session/deleted"),
                eq("_sandbox_state"),
                eq(SandboxStateSlotView.class)))
                .thenReturn(Optional.of(new SandboxStateSlotView("", true)));

        WorkspaceDiffService service = WorkspaceDiffService.forTest(
                tempDir.resolve("project"),
                mock(SandboxSnapshotSpec.class),
                store,
                JSON);

        assertThat(service.resolveSnapshotId("missing")).isEmpty();
        assertThat(service.resolveSnapshotId("deleted")).isEmpty();
        assertThat(service.resolveSnapshotId("")).isEmpty();
    }

    @Test
    void createDiff_returnsNullWhenSnapshotNotRestorable() throws Exception {
        AgentStateStore store = mock(AgentStateStore.class);
        String json = """
                {"snapshot":{"id":"snap-1"}}
                """;
        when(store.get(
                isNull(),
                eq("sandbox/session/s1"),
                eq("_sandbox_state"),
                eq(SandboxStateSlotView.class)))
                .thenReturn(Optional.of(new SandboxStateSlotView(json, false)));

        SandboxSnapshotSpec spec = mock(SandboxSnapshotSpec.class);
        SandboxSnapshot snapshot = mock(SandboxSnapshot.class);
        when(spec.build("snap-1")).thenReturn(snapshot);
        when(snapshot.isRestorable()).thenReturn(false);

        WorkspaceDiffService service = WorkspaceDiffService.forTest(
                tempDir.resolve("project"),
                spec,
                store,
                JSON);
        service.captureBaseline("u1", "s1");

        assertThat(service.createDiff("u1", "s1")).isNull();
    }
}
