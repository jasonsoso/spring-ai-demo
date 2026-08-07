package com.jason.demo.demo2.framework.delay.support;

import com.jason.demo.demo2.framework.rocketmq.DelayTimeLevel;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DelayTimeLevelMapperTest {

    @Test
    void twentySeconds_mapsToS30() {
        assertEquals(DelayTimeLevel.S_30, DelayTimeLevelMapper.mapAtLeast(Duration.ofSeconds(20)));
    }

    @Test
    void exactlyFiveSeconds_mapsToS5() {
        assertEquals(DelayTimeLevel.S_5, DelayTimeLevelMapper.mapAtLeast(Duration.ofSeconds(5)));
    }

    @Test
    void over24h_mapsToH24() {
        assertEquals(DelayTimeLevel.H_24, DelayTimeLevelMapper.mapAtLeast(Duration.ofHours(25)));
    }
}
