package com.jason.demo.demo2.framework.rocketmq.util;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionUtilsTest {

    @Test
    void afterCommitSyncExecute_runsImmediately_whenNoTransaction() {
        AtomicBoolean ran = new AtomicBoolean(false);
        TransactionUtils.afterCommitSyncExecute(() -> ran.set(true));
        assertThat(ran).isTrue();
    }

    @Test
    void afterCommitSyncExecute_runsAfterCommit_whenSynchronizationActive() {
        AtomicBoolean ran = new AtomicBoolean(false);
        TransactionSynchronizationManager.initSynchronization();
        try {
            TransactionUtils.afterCommitSyncExecute(() -> ran.set(true));
            assertThat(ran).isFalse();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCommit());
            assertThat(ran).isTrue();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
