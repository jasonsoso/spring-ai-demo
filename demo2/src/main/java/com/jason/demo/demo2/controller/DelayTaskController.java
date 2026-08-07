package com.jason.demo.demo2.controller;

import com.jason.demo.demo2.framework.delay.DelayTaskService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/demo/delay-tasks")
public class DelayTaskController {

    private final DelayTaskService delayTaskService;

    public DelayTaskController(DelayTaskService delayTaskService) {
        this.delayTaskService = delayTaskService;
    }

    @PostMapping("/{taskId}/cancel")
    public Map<String, Object> cancel(@PathVariable long taskId) {
        boolean ok = delayTaskService.cancelById(taskId);
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "pending task not found: " + taskId);
        }
        return Map.of("ok", true, "taskId", taskId);
    }

    @GetMapping
    public Object query(
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) String bizKey) {
        if (taskId != null) {
            return delayTaskService.get(taskId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found"));
        }
        if (bizKey != null && !bizKey.isBlank()) {
            return delayTaskService.listByBizKey(bizKey);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "provide taskId or bizKey");
    }
}
