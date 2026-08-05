package com.jason.demo.demo2.service;

import com.jason.demo.demo2.model.OrderDto;
import com.jason.demo.demo2.model.UserProfileAggregateResponse;
import com.jason.demo.demo2.model.UserProfileDto;
import com.jason.demo.demo2.parallel.MockOrderQuery;
import com.jason.demo.demo2.parallel.MockUserQuery;
import com.jason.demo.demo2.parallel.ParallelProperties;
import com.jason.demo.demo2.parallel.ParallelQuerySupport;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

@Service
public class ParallelProfileService {

    private final ParallelQuerySupport parallelQuerySupport;
    private final MockUserQuery mockUserQuery;
    private final MockOrderQuery mockOrderQuery;
    private final ParallelProperties properties;

    public ParallelProfileService(
            ParallelQuerySupport parallelQuerySupport,
            MockUserQuery mockUserQuery,
            MockOrderQuery mockOrderQuery,
            ParallelProperties properties) {
        this.parallelQuerySupport = parallelQuerySupport;
        this.mockUserQuery = mockUserQuery;
        this.mockOrderQuery = mockOrderQuery;
        this.properties = properties;
    }

    public UserProfileAggregateResponse load(
            String userId,
            long userDelayMs,
            boolean userFail,
            long orderDelayMs,
            boolean orderFail,
            Executor executor) {
        Map<String, Supplier<?>> tasks = new LinkedHashMap<>();
        tasks.put("user", () -> mockUserQuery.find(userId, userDelayMs, userFail));
        tasks.put("orders", () -> mockOrderQuery.findByUserId(userId, orderDelayMs, orderFail));

        Map<String, Object> raw = parallelQuerySupport.run(
                tasks, properties.getTimeout(), executor);

        UserProfileDto user = (UserProfileDto) raw.get("user");
        @SuppressWarnings("unchecked")
        List<OrderDto> orders = (List<OrderDto>) raw.get("orders");
        return new UserProfileAggregateResponse(user, orders);
    }
}
