package com.jason.demo.demo2.controller;

import com.jason.demo.demo2.framework.rocketmq.DelayTimeLevel;
import com.jason.demo.demo2.mq.model.OrderEvent;
import com.jason.demo.demo2.mq.model.OrderEventRequest;
import com.jason.demo.demo2.mq.publisher.OrderEventPublisher;
import com.jason.demo.demo2.mq.store.InMemoryOrderEventStore;
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
        return Map.of("ok", true, "mode", "sync", "orderId", event.getOrderId());
    }

    @PostMapping("/async")
    public Map<String, Object> async(@RequestBody OrderEventRequest request) {
        OrderEvent event = toEvent(request);
        publisher.sendAsync(event);
        return Map.of("ok", true, "mode", "async", "orderId", event.getOrderId());
    }

    @PostMapping("/orderly")
    public Map<String, Object> orderly(@RequestBody OrderEventRequest request) {
        OrderEvent event = toEvent(request);
        publisher.sendOrderly(event);
        return Map.of("ok", true, "mode", "orderly", "orderId", event.getOrderId());
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
        return Map.of("ok", true, "mode", "delay", "level", delayLevel.name(), "orderId", event.getOrderId());
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
        if (request == null || request.getOrderId() == null || request.getOrderId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "orderId is required");
        }
        String type = request.getType() == null || request.getType().isBlank() ? "CREATED" : request.getType();
        String payload = request.getPayload() == null ? "" : request.getPayload();
        return new OrderEvent(request.getOrderId(), type, payload, Instant.now());
    }
}
