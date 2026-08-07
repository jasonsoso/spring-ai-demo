package com.jason.demo.demo2.framework.id;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SnowflakePropertiesTest {

    @Test
    void defaults_matchSpec() {
        SnowflakeProperties props = new SnowflakeProperties();
        assertEquals("app:snowflake", props.getKeyPrefix());
        assertEquals(30, props.getLeaseTtlSeconds());
        assertEquals(10, props.getHeartbeatIntervalSeconds());
    }
}
