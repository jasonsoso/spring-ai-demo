package com.jason.demo.demo2.mq.store;

import com.jason.demo.demo2.mq.model.OrderEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryOrderEventStoreTest {

    @Test
    void appendAndFilterByOrderId() {
        InMemoryOrderEventStore store = new InMemoryOrderEventStore();
        store.append("concurrent", new OrderEvent("o1", "CREATED", "a", Instant.parse("2026-08-06T00:00:00Z")));
        store.append("orderly", new OrderEvent("o2", "PAID", "b", Instant.parse("2026-08-06T00:01:00Z")));
        assertThat(store.list("o1")).hasSize(1);
        store.clear();
        assertThat(store.list(null)).isEmpty();
    }
}
