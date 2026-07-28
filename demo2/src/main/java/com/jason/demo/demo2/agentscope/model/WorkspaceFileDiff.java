package com.jason.demo.demo2.agentscope.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkspaceFileDiff(
        String path,
        String changeType,
        int additions,
        int deletions,
        String oldHash,
        String newHash,
        String newContent) {
}
