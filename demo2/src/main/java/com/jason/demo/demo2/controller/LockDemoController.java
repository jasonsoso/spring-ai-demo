package com.jason.demo.demo2.controller;

import com.jason.demo.demo2.lock.LockKeys;
import com.jason.demo.demo2.model.LockDemoRequest;
import com.jason.demo.demo2.model.LockDemoResponse;
import com.jason.demo.demo2.service.LockDemoService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/demo/lock")
public class LockDemoController {

    private final LockDemoService lockDemoService;

    public LockDemoController(LockDemoService lockDemoService) {
        this.lockDemoService = lockDemoService;
    }

    @PostMapping("/submit")
    public LockDemoResponse submit(@Valid @RequestBody LockDemoRequest request) {
        String userId = (request.userId() == null || request.userId().isBlank())
                ? "anonymous"
                : request.userId().strip();
        int workMs = request.workMs() == null ? 3000 : request.workMs();
        String key = LockKeys.demoSubmitKey(userId, request.sessionId(), request.message());
        return lockDemoService.submitLocked(key, request.message(), workMs);
    }
}
