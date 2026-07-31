package com.jason.demo.demo2.agentscope.config;

import com.jason.demo.demo2.agentscope.sandbox.ActiveSandboxRegistry;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class AgentscopeSandboxFilesystemSpecTest {

    @Test
    void localMode_setsLocalSnapshotSpec() throws Exception {
        DockerFilesystemSpec spec = AgentScopeConfig.dockerFilesystemSpec(
                sandboxEnabledProperties(), new ActiveSandboxRegistry(), true);

        assertThat(dockerOwnedSnapshotSpec(spec)).isInstanceOf(LocalSnapshotSpec.class);
        assertThat(spec.getSnapshotSpecOverride()).isNull();
    }

    @Test
    void remoteMode_omitsLocalSnapshotOverride() throws Exception {
        DockerFilesystemSpec spec = AgentScopeConfig.dockerFilesystemSpec(
                sandboxEnabledProperties(), new ActiveSandboxRegistry(), false);

        assertThat(spec.getSnapshotSpecOverride()).isNull();
        assertThat(dockerOwnedSnapshotSpec(spec)).isInstanceOf(NoopSnapshotSpec.class);
    }

    private static DevAgentProperties sandboxEnabledProperties() {
        return new DevAgentProperties(
                "dev-task-agent",
                "short",
                ".",
                "workspace",
                new DevAgentProperties.Compaction(12, 2, "请整理会话：{messages}"),
                new DevAgentProperties.Model("", "https://api.deepseek.com", "deepseek-v4-pro"),
                null,
                null,
                null,
                new DevAgentProperties.Sandbox(
                        true,
                        "agentscope-java-sandbox:17",
                        "none",
                        "/workspace",
                        ".agentscope/sandbox-snapshots",
                        536870912L,
                        1L));
    }

    /**
     * {@link DockerFilesystemSpec#snapshotSpec(SandboxSnapshotSpec)} 写的是自身字段，
     * 不是父类 {@code snapshotSpecOverride}；单测用反射读取。
     */
    private static SandboxSnapshotSpec dockerOwnedSnapshotSpec(DockerFilesystemSpec spec)
            throws Exception {
        Field field = DockerFilesystemSpec.class.getDeclaredField("snapshotSpec");
        field.setAccessible(true);
        return (SandboxSnapshotSpec) field.get(spec);
    }
}
