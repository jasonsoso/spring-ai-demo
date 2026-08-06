package com.jason.demo.demo2.controller;

import com.jason.demo.demo2.framework.rocketmq.DelayTimeLevel;
import com.jason.demo.demo2.mq.InMemoryOrderEventStore;
import com.jason.demo.demo2.mq.OrderEvent;
import com.jason.demo.demo2.mq.OrderEventPublisher;
import com.jason.demo.demo2.mq.OrderEventRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/demo/mq/orders")
public class OrderMqController {

    private final OrderEventPublisher publisher;
    private final InMemoryOrderEventStore store;

    public OrderMqController(OrderEventPublisher publisher, InMemoryOrderEventStore store) {
        this.publisher = publisher;
        this.store = store;
    }

    @PostMapping("/sync")
    public Map<String, Object> sync(@RequestBody OrderEventRequest request) {
        OrderEvent event = toEvent(request);
        publisher.sendSync(event);
        return Map.of("ok", true, "mode", "sync", "orderId", event.orderId());
    }

    @PostMapping("/async")
    public Map<String, Object> async(@RequestBody OrderEventRequest request) {
        OrderEvent event = toEvent(request);
        publisher.sendAsync(event);
        return Map.of("ok", true, "mode", "async", "orderId", event.orderId());
    }

    @PostMapping("/orderly")
    public Map<String, Object> orderly(@RequestBody OrderEventRequest request) {
        OrderEvent event = toEvent(request);
        publisher.sendOrderly(event);
        return Map.of("ok", true, "mode", "orderly", "orderId", event.orderId());
    }

    @PostMapping("/delay")
    public Map<String, Object> delay(
            @RequestBody OrderEventRequest request,
            @RequestParam(defaultValue = "S_5") String level) {
        DelayTimeLevel delayLevel;
        try {
            delayLevel = DelayTimeLevel.valueOf(level);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid delay level: " + level);
        }
        OrderEvent event = toEvent(request);
        publisher.sendDelay(event, delayLevel);
        return Map.of("ok", true, "mode", "delay", "level", delayLevel.name(), "orderId", event.orderId());
    }

    @GetMapping("/events")
    public List<InMemoryOrderEventStore.StoredEvent> events(@RequestParam(required = false) String orderId) {
        return store.list(orderId);
    }

    @DeleteMapping("/events")
    public Map<String, Object> clear() {
        store.clear();
        return Map.of("ok", true);
    }

    private static OrderEvent toEvent(OrderEventRequest request) {
        if (request == null || request.orderId() == null || request.orderId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "orderId is required");
        }
        String type = request.type() == null || request.type().isBlank() ? "CREATED" : request.type();
        String payload = request.payload() == null ? "" : request.payload();
        return new OrderEvent(request.orderId(), type, payload, Instant.now());
    }
}
