package com.jason.demo.demo2.framework.rocketmq.util;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Executor;

/**
 * 事务感知执行工具：保证「落库成功后再发 MQ」一类场景。
 * <ul>
 *   <li>存在活跃事务同步 → 注册 {@code afterCommit} 回调</li>
 *   <li>无事务 → 立即执行（本地 Demo / 非事务调用同样可用）</li>
 * </ul>
 */
public final class TransactionUtils {

    private TransactionUtils() {
    }

    /** 事务提交后在当前线程同步执行；无事务则立刻执行。 */
    public static void afterCommitSyncExecute(Runnable runnable) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runnable.run();
                }
            });
        } else {
            runnable.run();
        }
    }

    /** 事务提交后提交到指定线程池异步执行；无事务则立刻异步提交。 */
    public static void afterCommitAsyncExecute(Executor executor, Runnable runnable) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(runnable);
                }
            });
        } else {
            executor.execute(runnable);
        }
    }
}
