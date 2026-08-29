package com.jason.demo.demo2.lock;

import com.baomidou.lock.LockFailureStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 定时任务抢不到锁时跳过本轮，不抛 {@code LockFailureException}，避免调度线程刷 ERROR。
 * 必须注册为 Spring Bean，lock4j 才认 {@code @Lock4j(failStrategy = ...)}。
 */
@Slf4j
@Component
public class SkipIfLockedFailureStrategy implements LockFailureStrategy {

    @Override
    public void onLockFailure(String key, Method method, Object[] arguments) {
        log.debug("skip {}, lock not acquired, key={}", method.getName(), key);
    }
}
