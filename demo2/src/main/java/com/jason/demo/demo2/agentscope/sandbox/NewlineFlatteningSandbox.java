package com.jason.demo.demo2.agentscope.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;

import java.io.InputStream;

/** 在 {@link Sandbox#exec} 入口把多行命令压成单行，规避 Windows docker 传参拆坏引号。 */
final class NewlineFlatteningSandbox implements Sandbox {

    private final Sandbox delegate;

    NewlineFlatteningSandbox(Sandbox delegate) {
        this.delegate = delegate;
    }

    @Override
    public void start() throws Exception {
        delegate.start();
    }

    @Override
    public void stop() throws Exception {
        delegate.stop();
    }

    @Override
    public void shutdown() throws Exception {
        delegate.shutdown();
    }

    @Override
    public void close() throws Exception {
        delegate.close();
    }

    @Override
    public boolean isRunning() {
        return delegate.isRunning();
    }

    @Override
    public SandboxState getState() {
        return delegate.getState();
    }

    @Override
    public ExecResult exec(RuntimeContext context, String command, Integer timeoutSeconds)
            throws Exception {
        return delegate.exec(context, MultilineShellFlattener.flatten(command), timeoutSeconds);
    }

    @Override
    public InputStream persistWorkspace() throws Exception {
        return delegate.persistWorkspace();
    }

    @Override
    public void hydrateWorkspace(InputStream workspaceTar) throws Exception {
        delegate.hydrateWorkspace(workspaceTar);
    }
}
