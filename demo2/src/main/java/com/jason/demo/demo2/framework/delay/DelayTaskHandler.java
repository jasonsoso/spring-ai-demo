package com.jason.demo.demo2.framework.delay;

import com.jason.demo.demo2.framework.delay.repository.DelayTaskEntity;

public interface DelayTaskHandler {

    String taskType();

    void handle(DelayTaskEntity task);
}
