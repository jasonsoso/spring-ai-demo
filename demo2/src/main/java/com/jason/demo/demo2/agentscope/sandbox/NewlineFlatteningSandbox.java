package com.jason.demo.demo2.agentscope.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * 在 {@link Sandbox#exec} 入口把多行命令压成单行，规避 Windows docker 传参拆坏引号；
 * shutdown 前对自管容器 {@code docker kill}，避免 AgentScope 默认 {@code docker stop --time=30}
 * 在镜像未处理 SIGTERM 时卡满 30 秒。
 */
public final class NewlineFlatteningSandbox implements Sandbox {

    private static final Logger log = LoggerFactory.getLogger(NewlineFlatteningSandbox.class);

    private final Sandbox delegate;
    private final ActiveSandboxRegistry registry;

    NewlineFlatteningSandbox(Sandbox delegate, ActiveSandboxRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    @Override
    public void start() throws Exception {
        delegate.start();
        // create 时 sessionId 可能尚未写入；start 后补登记。
        if (registry != null) {
            registry.register(this);
        }
    }

    @Override
    public void stop() throws Exception {
        delegate.stop();
    }

    @Override
    public void shutdown() throws Exception {
        forceKillOwnedContainer();
        delegate.shutdown();
    }

    /**
     * AgentScope DockerSandbox.shutdown 使用 {@code docker stop --time=30}；
     * 当前沙箱镜像入口未及时响应 SIGTERM 时会卡满宽限期。先 kill 再交给框架 rm。
     *
     * @return kill 耗时毫秒；未执行时为 -1
     */
    long forceKillOwnedContainer() {
        try {
            SandboxState state = delegate.getState();
            if (!(state instanceof DockerSandboxState dockerState)
                    || !dockerState.isContainerOwned()) {
                return -1L;
            }
            String containerId = dockerState.getContainerId();
            if (containerId == null || containerId.isBlank()) {
                return -1L;
            }
            long t0 = System.currentTimeMillis();
            Process process = new ProcessBuilder("docker", "kill", containerId)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("docker kill timed out for container {}", containerId);
            } else if (process.exitValue() != 0) {
                log.debug(
                        "docker kill exit={} for container {} (may already be stopped)",
                        process.exitValue(),
                        containerId);
            }
            return System.currentTimeMillis() - t0;
        } catch (Exception ex) {
            log.debug("forceKillOwnedContainer skipped: {}", ex.toString());
            return -1L;
        }
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
