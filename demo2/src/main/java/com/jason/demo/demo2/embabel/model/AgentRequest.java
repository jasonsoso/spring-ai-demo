package com.jason.demo.demo2.embabel.model;

import jakarta.validation.constraints.NotBlank;

public record AgentRequest(@NotBlank String message) {
}
