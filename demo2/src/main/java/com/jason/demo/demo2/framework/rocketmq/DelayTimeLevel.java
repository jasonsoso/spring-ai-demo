package com.jason.demo.demo2.framework.rocketmq;

import java.time.Duration;

/**
 * RocketMQ 固定延迟等级（共 18 档，对应客户端 {@code Message#setDelayTimeLevel}）。
 * <p>
 * 枚举名表示可读时长，{@link #getLevel()} 为 1～18 的底层 level 值；
 * {@link #getDelay()} 为对应真实时长，供延时任务映射使用。
 */
public enum DelayTimeLevel {
    S_1(1, "1s", Duration.ofSeconds(1)),
    S_5(2, "5s", Duration.ofSeconds(5)),
    S_10(3, "10s", Duration.ofSeconds(10)),
    S_30(4, "30s", Duration.ofSeconds(30)),
    M_1(5, "1m", Duration.ofMinutes(1)),
    M_2(6, "2m", Duration.ofMinutes(2)),
    M_5(7, "5m", Duration.ofMinutes(5)),
    M_10(8, "10m", Duration.ofMinutes(10)),
    M_15(9, "15m", Duration.ofMinutes(15)),
    M_30(10, "30m", Duration.ofMinutes(30)),
    H_1(11, "1h", Duration.ofHours(1)),
    H_2(12, "2h", Duration.ofHours(2)),
    H_3(13, "3h", Duration.ofHours(3)),
    H_5(14, "5h", Duration.ofHours(5)),
    H_6(15, "6h", Duration.ofHours(6)),
    H_10(16, "10h", Duration.ofHours(10)),
    H_12(17, "12h", Duration.ofHours(12)),
    H_24(18, "24h", Duration.ofHours(24));

    /** RocketMQ delayTimeLevel（1～18） */
    private final int level;
    /** 可读描述，如 {@code 5s} */
    private final String desc;
    private final Duration delay;

    DelayTimeLevel(int level, String desc, Duration delay) {
        this.level = level;
        this.desc = desc;
        this.delay = delay;
    }

    public int getLevel() {
        return level;
    }

    public String getDesc() {
        return desc;
    }

    public Duration getDelay() {
        return delay;
    }
}
