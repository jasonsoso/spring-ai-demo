package com.jason.demo.demo2.agentscope.service;

import com.jason.demo.demo2.agentscope.config.DevAgentProperties;
import com.jason.demo.demo2.agentscope.model.DevAgentEvent;
import com.jason.demo.demo2.agentscope.model.DevAgentRequest;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class DevAgentService {

    private final HarnessAgent agentscopeDevAgent;
    private final DevAgentProperties properties;

    public DevAgentService(HarnessAgent agentscopeDevAgent, DevAgentProperties properties) {
        this.agentscopeDevAgent = agentscopeDevAgent;
        this.properties = properties;
    }

    public Flux<DevAgentEvent> ask(DevAgentRequest request) {
        String sessionId = request.sessionId();
        String apiKey = properties.model().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return Flux.just(
                    DevAgentEvent.session(sessionId),
                    DevAgentEvent.error(sessionId, "DEEPSEEK_API_KEY is not configured"));
        }

        RuntimeContext.Builder contextBuilder = RuntimeContext.builder()
                .sessionId(sessionId);
        if (request.userId() != null && !request.userId().isBlank()) {
            contextBuilder.userId(request.userId().strip());
        }
        RuntimeContext context = contextBuilder.build();

        Flux<DevAgentEvent> messages = agentscopeDevAgent
                .streamEvents(request.message(), context)
                .ofType(TextBlockDeltaEvent.class)
                .map(event -> DevAgentEvent.message(sessionId, event.getDelta()));

        return Flux.concat(
                        Mono.just(DevAgentEvent.session(sessionId)),
                        messages,
                        Mono.just(DevAgentEvent.done(sessionId)))
                .onErrorResume(ex -> Flux.just(
                        DevAgentEvent.error(
                                sessionId,
                                ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage())));
    }
}
