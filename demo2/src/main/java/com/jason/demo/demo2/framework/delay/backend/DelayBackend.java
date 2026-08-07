package com.jason.demo.demo2.framework.delay.backend;

import java.time.Duration;

public interface DelayBackend {

    String name();

    void schedule(long taskId, Duration delay);

    void cancel(long taskId);
}
