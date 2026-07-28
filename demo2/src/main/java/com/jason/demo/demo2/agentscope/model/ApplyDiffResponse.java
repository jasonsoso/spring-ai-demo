package com.jason.demo.demo2.agentscope.model;

public record ApplyDiffResponse(
        String diffId,
        boolean applied,
        String message) {
}
