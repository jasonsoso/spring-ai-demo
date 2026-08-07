package com.jason.demo.demo2.order;

import com.jason.demo.demo2.order.repository.OrderEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/demo/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateOrderRequest request) {
        if (request == null || request.getAmount() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount is required");
        }
        Duration delay = parseDelay(request.getDelay());
        return orderService.create(request.getAmount(), delay);
    }

    @PostMapping("/{id}/pay")
    public Map<String, Object> pay(@PathVariable("id") long id) {
        return orderService.pay(id);
    }

    @GetMapping("/{id}")
    public OrderEntity get(@PathVariable("id") long id) {
        return orderService.get(id);
    }

    private static Duration parseDelay(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            if (value.startsWith("P") || value.startsWith("p")) {
                return Duration.parse(value);
            }
            if (value.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
            }
            if (value.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)));
            }
            if (value.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1)));
            }
            if (value.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(value.substring(0, value.length() - 1)));
            }
            return Duration.parse(value);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid delay: " + raw);
        }
    }
}
