package com.jason.demo.demo2.agentscope.sandbox;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

/**
 * 包装 {@link DockerSandboxClient}：所有沙箱 exec 先经 {@link MultilineShellFlattener}，
 * 修复 Windows 下 AgentScope {@code edit_file} 多行 heredoc 命令失败；
 * 并在 create/resume/delete 时维护 {@link ActiveSandboxRegistry}。
 */
public final class NewlineFlatteningDockerSandboxClient
        implements SandboxClient<DockerSandboxClientOptions> {

    private final DockerSandboxClient delegate;
    private final ActiveSandboxRegistry registry;

    public NewlineFlatteningDockerSandboxClient() {
        this(new DockerSandboxClient(), new ActiveSandboxRegistry());
    }

    NewlineFlatteningDockerSandboxClient(DockerSandboxClient delegate) {
        this(delegate, new ActiveSandboxRegistry());
    }

    public NewlineFlatteningDockerSandboxClient(ActiveSandboxRegistry registry) {
        this(new DockerSandboxClient(), registry);
    }

    public NewlineFlatteningDockerSandboxClient(
            DockerSandboxClient delegate, ActiveSandboxRegistry registry) {
        this.delegate = delegate;
        this.registry = registry == null ? new ActiveSandboxRegistry() : registry;
    }

    @Override
    public Sandbox create(
            WorkspaceSpec workspaceSpec,
            SandboxSnapshotSpec snapshotSpec,
            DockerSandboxClientOptions options) {
        Sandbox sandbox = new NewlineFlatteningSandbox(
                delegate.create(workspaceSpec, snapshotSpec, options));
        registry.register(sandbox);
        return sandbox;
    }

    @Override
    public Sandbox resume(SandboxState state) {
        Sandbox sandbox = new NewlineFlatteningSandbox(delegate.resume(state));
        registry.register(sandbox);
        return sandbox;
    }

    @Override
    public void delete(Sandbox sandbox) {
        try {
            delegate.delete(sandbox);
        } finally {
            registry.unregister(sandbox);
        }
    }

    @Override
    public String serializeState(SandboxState state) {
        return delegate.serializeState(state);
    }

    @Override
    public SandboxState deserializeState(String serialized) {
        return delegate.deserializeState(serialized);
    }

    @Override
    public SandboxState deserializeState(String serialized, SandboxSnapshotSpec snapshotSpec) {
        return delegate.deserializeState(serialized, snapshotSpec);
    }
}
