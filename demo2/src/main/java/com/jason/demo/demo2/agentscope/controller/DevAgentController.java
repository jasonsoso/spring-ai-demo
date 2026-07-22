package com.jason.demo.demo2.agentscope.controller;

import com.jason.demo.demo2.agentscope.model.DevAgentConfirmRequest;
import com.jason.demo.demo2.agentscope.model.DevAgentEvent;
import com.jason.demo.demo2.agentscope.model.DevAgentRequest;
import com.jason.demo.demo2.agentscope.service.DevAgentService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/agentscope/dev-agent")
public class DevAgentController {

    private final DevAgentService devAgentService;

    public DevAgentController(DevAgentService devAgentService) {
        this.devAgentService = devAgentService;
    }

    @PostMapping(path = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<DevAgentEvent> ask(@Valid @RequestBody DevAgentRequest request) {
        return devAgentService.ask(request);
    }

    @PostMapping(path = "/confirm", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<DevAgentEvent> confirm(@Valid @RequestBody DevAgentConfirmRequest request) {
        return devAgentService.confirm(request);
    }
}
