package com.jason.demo.demo2.product;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisStockLuaScriptTest {

    @Test
    void confirmAndRelease_useGetThenDel_notGetdel() throws Exception {
        String confirm = read("lua/stock-confirm.lua");
        String release = read("lua/stock-release.lua");

        assertFalse(confirm.contains("GETDEL"));
        assertFalse(release.contains("GETDEL"));
        assertTrue(confirm.contains("redis.call('GET'"));
        assertTrue(confirm.contains("redis.call('DEL'"));
        assertTrue(release.contains("redis.call('GET'"));
        assertTrue(release.contains("redis.call('DEL'"));
    }

    @Test
    void reserve_containsSetnxHincrbyXadd() throws Exception {
        String reserve = read("lua/stock-reserve.lua");
        assertTrue(reserve.contains("SETNX"));
        assertTrue(reserve.contains("HINCRBY"));
        assertTrue(reserve.contains("XADD"));
    }

    @Test
    void adjust_containsHset_notXadd() throws Exception {
        String adjust = read("lua/stock-adjust.lua");
        assertTrue(adjust.contains("HSET"));
        assertFalse(adjust.contains("XADD"));
    }

    private static String read(String location) throws Exception {
        return new String(new ClassPathResource(location).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
