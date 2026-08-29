package com.jason.demo.demo2.lock;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class SkipIfLockedFailureStrategyTest {

    @Test
    void onLockFailure_doesNotThrow() {
        SkipIfLockedFailureStrategy strategy = new SkipIfLockedFailureStrategy();
        assertThatCode(() -> strategy.onLockFailure("product:stock:reconcile", Object.class.getMethods()[0], new Object[0]))
                .doesNotThrowAnyException();
    }
}
