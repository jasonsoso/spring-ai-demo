package com.jason.demo.demo2.controller;

import com.jason.demo.demo2.model.UserProfileAggregateResponse;
import com.jason.demo.demo2.service.ParallelProfileService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.Executor;

@RestController
@RequestMapping("/demo/parallel")
public class ParallelProfileController {

    private final ParallelProfileService parallelProfileService;
    private final Executor parallelVirtualExecutor;
    private final Executor parallelJdk8Executor;

    public ParallelProfileController(
            ParallelProfileService parallelProfileService,
            @Qualifier("parallelVirtualExecutor") Executor parallelVirtualExecutor,
            @Qualifier("parallelJdk8Executor") Executor parallelJdk8Executor) {
        this.parallelProfileService = parallelProfileService;
        this.parallelVirtualExecutor = parallelVirtualExecutor;
        this.parallelJdk8Executor = parallelJdk8Executor;
    }

    @GetMapping("/virtual/user-profile")
    public UserProfileAggregateResponse virtualProfile(
            @RequestParam(defaultValue = "u1") String userId,
            @RequestParam(defaultValue = "200") long userDelayMs,
            @RequestParam(defaultValue = "300") long orderDelayMs,
            @RequestParam(defaultValue = "false") boolean userFail,
            @RequestParam(defaultValue = "false") boolean orderFail) {
        return parallelProfileService.load(
                userId, userDelayMs, userFail, orderDelayMs, orderFail,
                parallelVirtualExecutor);
    }

    @GetMapping("/jdk8/user-profile")
    public UserProfileAggregateResponse jdk8Profile(
            @RequestParam(defaultValue = "u1") String userId,
            @RequestParam(defaultValue = "200") long userDelayMs,
            @RequestParam(defaultValue = "300") long orderDelayMs,
            @RequestParam(defaultValue = "false") boolean userFail,
            @RequestParam(defaultValue = "false") boolean orderFail) {
        return parallelProfileService.load(
                userId, userDelayMs, userFail, orderDelayMs, orderFail,
                parallelJdk8Executor);
    }
}
