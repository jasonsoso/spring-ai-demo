package com.jason.demo.demo2.framework.rocketmq;

/**
 * RocketMQ 延迟消息等级（1s ~ 24h）。
 */
public enum DelayTimeLevel {
    S_1(1, "1s"),
    S_5(2, "5s"),
    S_10(3, "10s"),
    S_30(4, "30s"),
    M_1(5, "1m"),
    M_2(6, "2m"),
    M_5(7, "5m"),
    M_10(8, "10m"),
    M_15(9, "15m"),
    M_30(10, "30m"),
    H_1(11, "1h"),
    H_2(12, "2h"),
    H_3(13, "3h"),
    H_5(14, "5h"),
    H_6(15, "6h"),
    H_10(16, "10h"),
    H_12(17, "12h"),
    H_24(18, "24h");

    private final int level;
    private final String desc;

    DelayTimeLevel(int level, String desc) {
        this.level = level;
        this.desc = desc;
    }

    public int getLevel() {
        return level;
    }

    public String getDesc() {
        return desc;
    }
}
