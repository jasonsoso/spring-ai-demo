package com.jason.demo.demo2.agentscopea2a.server;

import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.spring.boot.a2a.controller.A2aJsonRpcController;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@RequestMapping("/agentscope-a2a")
public class RiskReviewA2aController {

    private static final Logger log = LoggerFactory.getLogger(RiskReviewA2aController.class);

    private final A2aJsonRpcController delegate;

    public RiskReviewA2aController(AgentScopeA2aServer server) {
        this.delegate = new A2aJsonRpcController(server);
    }

    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public Object handleRequest(
            @RequestBody String body,
            @RequestHeader Map<String, String> headers) {
        log.info("[A2A Server] request headers={}, body={}", headers, body);
        Object response = delegate.handleRequest(body, headers);
        if (response instanceof Publisher<?> publisher) {
            return Flux.from(publisher)
                    .doOnNext(item -> log.info("[A2A Server] response={}", item))
                    .doOnError(error -> log.error("[A2A Server] response failed", error));
        }
        log.info("[A2A Server] response={}", response);
        return response;
    }
}
