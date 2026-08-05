package com.jason.demo.demo2.service;

import com.baomidou.lock.annotation.Lock4j;
import com.jason.demo.demo2.model.LockDemoResponse;
import org.springframework.stereotype.Service;

@Service
public class LockDemoService {

    @Lock4j(keys = {"#key"}, acquireTimeout = 0, expire = 30000)
    public LockDemoResponse submitLocked(String key, String echo, int workMs) {
        long start = System.nanoTime();
        try {
            Thread.sleep(workMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        return LockDemoResponse.ok(key, elapsedMs, echo);
    }
}
