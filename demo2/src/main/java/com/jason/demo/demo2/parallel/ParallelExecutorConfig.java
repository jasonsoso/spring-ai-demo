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

/**
 * 并行查询 Demo 的两类 Executor：虚拟线程 vs 手写 JDK8 风格 {@link ThreadPoolExecutor}。
 * <p>
 * 禁止用 {@code Executors.newFixedThreadPool} / {@code newCachedThreadPool}：前者内部是无界
 * {@code LinkedBlockingQueue}，队列几乎永不满，{@code maximumPoolSize} 和拒绝策略都很难被触发。
 * <p>
 * 任务进入 {@code ThreadPoolExecutor} 的顺序（理解下面 7 个构造参数怎么配合）：
 * <ol>
 *   <li>当前线程数 &lt; core → 新建线程执行（先凑满核心，不是先排队）</li>
 *   <li>已达 core → 任务进入 workQueue</li>
 *   <li>队列满且线程数 &lt; max → 再建线程直到 max</li>
 *   <li>队列满且已达 max → 走拒绝策略 handler</li>
 * </ol>
 * 即：先核心 → 再入队 → 队列满才扩到 max → 再满才拒绝。不是「先扩到 max 再排队」。
 */
@Configuration
public class ParallelExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(ParallelExecutorConfig.class);

    private ExecutorService virtualExecutor;
    private ThreadPoolExecutor jdk8Executor;
    private final ParallelProperties properties;

    public ParallelExecutorConfig(ParallelProperties properties) {
        this.properties = properties;
    }

    /**
     * Java 21 虚拟线程：每个任务一条虚拟线程，由 JVM 调度到少量载体线程上。
     * 适合本 Demo 的 I/O/sleep 型并行查询；无需配置池大小。关闭时 {@link #shutdown()}。
     */
    @Bean(name = "parallelVirtualExecutor")
    public ExecutorService parallelVirtualExecutor() {
        virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        return virtualExecutor;
    }

    /**
     * JDK8 风格平台线程池，进程内单例 Bean。参数来自 {@code demo.parallel.jdk8.*}，
     * {@code core/max <= 0} 时按 CPU 核数 N 回退：core=N、max=N*2。
     */
    @Bean(name = "parallelJdk8Executor")
    public ExecutorService parallelJdk8Executor() {
        int n = Runtime.getRuntime().availableProcessors();
        ParallelProperties.Jdk8 jdk8 = properties.getJdk8();
        // 核心默认跟 CPU 核数走：本 Demo 查询偏 I/O，N 只是起点；CPU 密集一般也不宜远超 N。
        int core = jdk8.getCorePoolSize() > 0 ? jdk8.getCorePoolSize() : n;
        // I/O 等待多时略放大到 2N，让队列满后还能再开一批线程；必须 >= core，否则构造器会抛异常。
        int max = jdk8.getMaxPoolSize() > 0 ? jdk8.getMaxPoolSize() : core * 2;
        if (max < core) {
            max = core;
        }
        // keepAlive 至少 1s：避免配成 0 导致非核心线程刚扩出来就被立刻回收。
        long keepAliveSeconds = Math.max(1L, jdk8.getKeepAlive().toSeconds());
        // 有界队列至少 1：容量 0 的 ArrayBlockingQueue 合法但毫无缓冲，几乎立刻扩容/拒绝。
        int capacity = Math.max(1, jdk8.getQueueCapacity());

        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "parallel-jdk8-" + seq.getAndIncrement());
                // 非守护线程：进程退出时不会被 JVM 直接掐掉，交给 @PreDestroy 的 shutdown 收口。
                t.setDaemon(false);
                return t;
            }
        };

        jdk8Executor = new ThreadPoolExecutor(
                // 1. corePoolSize：常驻线程数下限。默认不因 keepAlive 回收（未开 allowCoreThreadTimeOut）。
                //    提交时若池内线程 < core，即使已有空闲线程也会先新建，直到凑满核心。
                core,
                // 2. maximumPoolSize：含核心在内的上限。只有 workQueue 满了才会从 core 扩到 max；
                //    若队列很大而 max 只比 core 大一点，扩容几乎用不上。
                max,
                // 3. keepAliveTime：仅作用于「超出 core 的临时线程」。空闲超过该时间回收，脉冲流量后少占平台线程。
                keepAliveSeconds,
                // 4. unit：keepAliveTime 的时间单位。和配置 demo.parallel.jdk8.keep-alive（默认 60s）对齐。
                TimeUnit.SECONDS,
                // 5. workQueue：核心线程都忙时，新任务先排队。用有界 ArrayBlockingQueue（默认容量 200）：
                //    满了才会扩容/拒绝，形成背压，避免任务无限堆积占内存。
                //    不用 LinkedBlockingQueue（newFixedThreadPool 那种无界队列），否则 max 和拒绝策略形同虚设；
                //    不用 SynchronousQueue（不缓冲、来一个就找线程），那是 cached 风格，本 Demo 不采用。
                new ArrayBlockingQueue<>(capacity),
                // 6. threadFactory：自定义线程名 parallel-jdk8-1、2…，方便日志和 jstack 对照；不要用默认无名字工厂。
                factory,
                // 7. handler：队列满且已达 max（或池已 shutdown）时怎么处理新任务。
                //    默认 CallerRunsPolicy：提交线程自己跑，温和背压、尽量不丢任务。
                //    拒绝发生在「提交进池」阶段，早于并行聚合的墙钟超时。
                resolveHandler(jdk8.getRejectedPolicy()));
        log.info("parallelJdk8Executor core={}, max={}, queue={}, policy={}",
                core, max, capacity, jdk8.getRejectedPolicy());
        return jdk8Executor;
    }

    /**
     * 配置串映射到 JDK 四种拒绝策略：
     * <ul>
     *   <li>{@code caller_runs}（默认）：调用线程执行 —— 拖慢提交方，尽量不丢</li>
     *   <li>{@code abort}：抛 RejectedExecutionException —— 快速失败，由上层记该路失败</li>
     *   <li>{@code discard}：丢掉新任务 —— 可丢的旁路才用，主链路慎用</li>
     *   <li>{@code discard_oldest}：丢掉队列最老的再入队 —— 只要最新数据时可丢旧任务</li>
     * </ul>
     * 未知/空值回退 CallerRuns，避免配错导致静默丢任务。
     */
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

    /**
     * Bean 销毁时关池，避免平台线程泄漏。
     * 虚拟线程 Executor 直接 shutdown；JDK8 池先 shutdown（不再接新任务、已提交的做完），
     * 5s 内未结束再 shutdownNow（尝试中断正在跑的任务）。
     */
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
