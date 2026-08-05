package com.jason.demo.demo2.service;

import com.jason.demo.demo2.model.UserProfileAggregateResponse;
import com.jason.demo.demo2.parallel.MockOrderQuery;
import com.jason.demo.demo2.parallel.MockUserQuery;
import com.jason.demo.demo2.parallel.ParallelProperties;
import com.jason.demo.demo2.parallel.ParallelQuerySupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class ParallelProfileServiceTest {

    private ExecutorService executor;
    private ParallelProfileService service;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        ParallelProperties props = new ParallelProperties();
        props.setTimeout(Duration.ofMillis(800));
        service = new ParallelProfileService(
                new ParallelQuerySupport(),
                new MockUserQuery(),
                new MockOrderQuery(),
                props);
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void load_success_returnsUserAndOrders() {
        UserProfileAggregateResponse resp = service.load(
                "u1", 50, false, 50, false, executor);
        assertThat(resp.user()).isNotNull();
        assertThat(resp.user().userId()).isEqualTo("u1");
        assertThat(resp.orders()).isNotNull().isNotEmpty();
    }

    @Test
    void load_orderFails_userStillPresent() {
        UserProfileAggregateResponse resp = service.load(
                "u1", 50, false, 50, true, executor);
        assertThat(resp.user()).isNotNull();
        assertThat(resp.orders()).isNull();
    }

    @Test
    void load_orderTooSlow_ordersNullWithinBudget() {
        long start = System.nanoTime();
        UserProfileAggregateResponse resp = service.load(
                "u1", 50, false, 5_000, false, executor);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(resp.user()).isNotNull();
        assertThat(resp.orders()).isNull();
        assertThat(elapsedMs).isLessThan(3_000L);
    }
}
