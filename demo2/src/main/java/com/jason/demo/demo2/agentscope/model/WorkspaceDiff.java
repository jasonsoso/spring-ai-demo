package com.jason.demo.demo2.agentscope.model;

import java.util.List;

public record WorkspaceDiff(
        String diffId,
        String userId,
        String sessionId,
        String baselineId,
        List<WorkspaceFileDiff> files,
        String unifiedDiff) {

    public boolean isEmpty() {
        return files == null || files.isEmpty();
    }
}
