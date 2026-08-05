package com.jason.demo.demo2.parallel;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class ParallelExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(ParallelExecutorConfig.class);

    private ExecutorService virtualExecutor;
    private ThreadPoolExecutor jdk8Executor;
    private final ParallelProperties properties;

    public ParallelExecutorConfig(ParallelProperties properties) {
        this.properties = properties;
    }

    @Bean(name = "parallelVirtualExecutor")
    public ExecutorService parallelVirtualExecutor() {
        virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        return virtualExecutor;
    }

    @Bean(name = "parallelJdk8Executor")
    public ExecutorService parallelJdk8Executor() {
        int n = Runtime.getRuntime().availableProcessors();
        ParallelProperties.Jdk8 jdk8 = properties.getJdk8();
        int core = jdk8.getCorePoolSize() > 0 ? jdk8.getCorePoolSize() : n;
        int max = jdk8.getMaxPoolSize() > 0 ? jdk8.getMaxPoolSize() : core * 2;
        if (max < core) {
            max = core;
        }
        long keepAliveSeconds = Math.max(1L, jdk8.getKeepAlive().toSeconds());
        int capacity = Math.max(1, jdk8.getQueueCapacity());

        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "parallel-jdk8-" + seq.getAndIncrement());
                t.setDaemon(false);
                return t;
            }
        };

        jdk8Executor = new ThreadPoolExecutor(
                core,
                max,
                keepAliveSeconds,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity),
                factory,
                resolveHandler(jdk8.getRejectedPolicy()));
        log.info("parallelJdk8Executor core={}, max={}, queue={}, policy={}",
                core, max, capacity, jdk8.getRejectedPolicy());
        return jdk8Executor;
    }

    static RejectedExecutionHandler resolveHandler(String policy) {
        if (policy == null) {
            return new ThreadPoolExecutor.CallerRunsPolicy();
        }
        return switch (policy.trim().toLowerCase()) {
            case "abort" -> new ThreadPoolExecutor.AbortPolicy();
            case "discard" -> new ThreadPoolExecutor.DiscardPolicy();
            case "discard_oldest" -> new ThreadPoolExecutor.DiscardOldestPolicy();
            default -> new ThreadPoolExecutor.CallerRunsPolicy();
        };
    }

    @PreDestroy
    public void shutdown() {
        if (virtualExecutor != null) {
            virtualExecutor.shutdown();
        }
        if (jdk8Executor != null) {
            jdk8Executor.shutdown();
            try {
                if (!jdk8Executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    jdk8Executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                jdk8Executor.shutdownNow();
            }
        }
    }
}
