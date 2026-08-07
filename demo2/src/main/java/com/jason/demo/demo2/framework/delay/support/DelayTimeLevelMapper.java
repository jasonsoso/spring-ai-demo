package com.jason.demo.demo2.framework.delay.support;

import com.jason.demo.demo2.framework.rocketmq.DelayTimeLevel;

import java.time.Duration;

/**
 * 将目标延时映射为不小于该时长的最小 RocketMQ {@link DelayTimeLevel}。
 */
public final class DelayTimeLevelMapper {

    private DelayTimeLevelMapper() {
    }

    public static DelayTimeLevel mapAtLeast(Duration delay) {
        if (delay == null || delay.isNegative() || delay.isZero()) {
            return DelayTimeLevel.S_1;
        }
        long seconds = delay.toSeconds();
        for (DelayTimeLevel level : DelayTimeLevel.values()) {
            if (level.getDelay().toSeconds() >= seconds) {
                return level;
            }
        }
        return DelayTimeLevel.H_24;
    }
}
