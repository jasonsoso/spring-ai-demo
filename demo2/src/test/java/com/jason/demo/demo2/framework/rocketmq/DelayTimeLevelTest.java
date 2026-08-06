package com.jason.demo.demo2.framework.rocketmq;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DelayTimeLevelTest {

    @Test
    void levels_coverOneToEighteen() {
        assertThat(DelayTimeLevel.values()).hasSize(18);
        assertThat(DelayTimeLevel.S_1.getLevel()).isEqualTo(1);
        assertThat(DelayTimeLevel.H_24.getLevel()).isEqualTo(18);
        assertThat(DelayTimeLevel.S_5.getDesc()).isEqualTo("5s");
    }
}
