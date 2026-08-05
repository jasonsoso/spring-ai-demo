package com.jason.demo.demo2.service;

import com.jason.demo.demo2.lock.LockKeys;
import com.jason.demo.demo2.model.LockDemoResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LockDemoServiceTest {

    @Test
    void submitLocked_returnsElapsedWithoutLockAspect() {
        LockDemoService service = new LockDemoService();
        String key = LockKeys.demoSubmitKey("u", "s", "hi");
        LockDemoResponse resp = service.submitLocked(key, "hi", 50);
        assertThat(resp.locked()).isTrue();
        assertThat(resp.key()).isEqualTo(key);
        assertThat(resp.elapsedMs()).isGreaterThanOrEqualTo(50);
        assertThat(resp.echo()).isEqualTo("hi");
    }
}
