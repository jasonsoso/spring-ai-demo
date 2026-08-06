package com.jason.demo.demo2.mq;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class InMemoryOrderEventStore {

    public record StoredEvent(String channel, OrderEvent event) {
    }

    private final CopyOnWriteArrayList<StoredEvent> events = new CopyOnWriteArrayList<>();

    public void append(String channel, OrderEvent event) {
        events.add(new StoredEvent(channel, event));
    }

    public List<StoredEvent> list(String orderIdFilter) {
        if (orderIdFilter == null || orderIdFilter.isBlank()) {
            return List.copyOf(events);
        }
        List<StoredEvent> filtered = new ArrayList<>();
        for (StoredEvent stored : events) {
            if (orderIdFilter.equals(stored.event().orderId())) {
                filtered.add(stored);
            }
        }
        return List.copyOf(filtered);
    }

    public void clear() {
        events.clear();
    }
}
