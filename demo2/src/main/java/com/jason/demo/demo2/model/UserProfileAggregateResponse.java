package com.jason.demo.demo2.model;

import java.util.List;

public record UserProfileAggregateResponse(UserProfileDto user, List<OrderDto> orders) {
}
