package com.jason.demo.demo2.framework.id;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SnowflakeIdGeneratorTest {

    @Test
    void nextId_isUniqueAndPositive() {
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1, 1);
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            long id = gen.nextId();
            assertTrue(id > 0);
            assertTrue(ids.add(id));
        }
    }
}
