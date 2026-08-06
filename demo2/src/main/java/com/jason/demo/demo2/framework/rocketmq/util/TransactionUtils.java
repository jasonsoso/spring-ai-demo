package com.jason.demo.demo2.framework.rocketmq.util;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Executor;

/**
 * 事务工具：有活跃同步则 afterCommit，否则立即执行。
 */
public final class TransactionUtils {

    private TransactionUtils() {
    }

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
