# Workspace Diff Writeback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将 Docker Sandbox 中的代码修改生成受控 unified diff，经用户确认后安全回写到 `workspace/project`。

**Architecture:** 复用 AgentScope 的 `LocalSnapshotSpec`/`SandboxSnapshot.restore()` 读取 Session 最终快照；服务端以宿主基线和沙箱快照生成 diff。Diff 通过现有 SSE 流展示，用户确认后调用新增的 `/apply-diff` 接口，经过路径与基线校验后原子回写。

**Tech Stack:** Spring Boot、Reactor SSE、AgentScope Harness 2.0.0、Jackson、Java NIO、JUnit 5、AssertJ。

## Global Constraints

- 只允许回写 `demo2/workspace/project`。
- 只在用户明确批准 Diff 后回写。
- 不依赖模型自行生成的 diff；Diff 必须由服务端根据基线和沙箱快照生成。
- 宿主文件发生基线冲突时拒绝回写，不覆盖用户修改。
- 保持现有 `/confirm` 工具调用确认流程不变。
- 不创建 Git commit，除非用户明确要求。

---

### Task 1: 建立 Diff 数据模型与事件

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/WorkspaceDiff.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/WorkspaceFileDiff.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/DevAgentEventType.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/DevAgentEvent.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/agentscope/model/DevAgentEventTest.java`

**Interfaces:**
- `WorkspaceDiff` 保存 `diffId`、`sessionId`、基线摘要、文件变更列表和 unified diff。
- `WorkspaceFileDiff` 保存相对路径、变更类型、增删行数、旧内容 hash、新内容 hash。
- 新增 `WORKSPACE_DIFF` 事件类型及 `DevAgentEvent.workspaceDiff(...)` 工厂方法。

- [ ] 为 Diff DTO 和事件序列化写失败测试。
- [ ] 运行 `mvn -q -Dtest=DevAgentEventTest test`，确认测试先失败。
- [ ] 实现不可变 record、JSON 字段和事件工厂。
- [ ] 重新运行目标测试，确认通过。

### Task 2: 实现宿主基线与安全路径校验

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/diff/WorkspaceBaselineStore.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/diff/WorkspacePathGuard.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/WorkspaceBaseline.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/agentscope/diff/WorkspacePathGuardTest.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/agentscope/diff/WorkspaceBaselineStoreTest.java`

**Interfaces:**
- `WorkspaceBaselineStore.capture(userId, sessionId, Path projectRoot)` 返回带文件 hash 的 `WorkspaceBaseline`。
- `WorkspaceBaselineStore.get(userId, sessionId, baselineId)` 读取当前会话基线。
- `WorkspacePathGuard.requireProjectPath(String relativePath)` 只返回 `projectRoot.resolve(relativePath).normalize()` 位于 projectRoot 内的路径。

- [ ] 覆盖绝对路径、`..` 越界、`.git` 和 project 内合法路径。
- [ ] 覆盖基线文件 hash 计算和会话隔离。
- [ ] 实现内存或本地受控存储，先不写入 `workspace/project`。
- [ ] 运行 Diff 领域测试并确认通过。

### Task 3: 从 AgentScope snapshot 生成 unified diff

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/diff/WorkspaceDiffService.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/diff/SnapshotWorkspaceExtractor.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/WorkspaceDiffException.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/agentscope/diff/WorkspaceDiffServiceTest.java`

**Interfaces:**
- `SnapshotWorkspaceExtractor.extract(String sessionId)` 使用 `LocalSnapshotSpec.build(sessionId).restore()` 解压到临时目录，并返回其中的 project 根目录。
- `WorkspaceDiffService.createDiff(userId, sessionId)` 返回 `WorkspaceDiff`；没有文件变化时返回空结果。
- 仅比较快照中的 `project/`，不把 `AGENTS.md`、skills、memory 或 snapshot 元数据纳入回写。

- [ ] 使用固定临时快照 fixture 覆盖新增、修改、删除文件。
- [ ] 验证生成的 unified diff 和文件 hash。
- [ ] 验证快照缺失、损坏和路径越界返回明确异常。
- [ ] 实现临时目录清理和最大文件大小限制。
- [ ] 运行 Diff 服务测试并确认通过。

### Task 4: 将 Diff 接入 Agent SSE 流

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/service/DevAgentService.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java`

**Interfaces:**
- 在 `askAfterContext` 和确认后的 Agent 流完成后，按 `Agent events → WORKSPACE_DIFF → DONE` 发送。
- 只在 `sandbox.enabled=true` 且存在实际文件变化时发送 Diff。
- Agent 执行失败、用户拒绝工具调用或快照不可用时不发送可回写 Diff。

- [ ] 添加服务测试，验证修改完成后产生 Diff 事件。
- [ ] 添加无变更和非沙箱模式测试。
- [ ] 接入 `WorkspaceBaselineStore` 和 `WorkspaceDiffService`。
- [ ] 运行现有 `DevAgentServiceTest` 与新增测试。

### Task 5: 增加 apply-diff 回写接口

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/ApplyDiffRequest.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/ApplyDiffResponse.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/diff/WorkspacePatchService.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/controller/DevAgentController.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/agentscope/diff/WorkspacePatchServiceTest.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/agentscope/controller/DevAgentControllerTest.java`

**Interfaces:**
- 新增 `POST /agentscope/dev-agent/apply-diff`。
- 请求包含 `userId`、`sessionId`、`diffId`、`approved`。
- `approved=false` 只丢弃待回写 Diff；`approved=true` 执行基线、路径和 hash 校验。
- `WorkspacePatchService.apply(...)` 先应用到临时目录，成功后原子替换目标文件。

- [ ] 测试批准、拒绝、未知 diffId、Session 不匹配。
- [ ] 测试宿主文件被修改后的基线冲突。
- [ ] 测试越界路径和 `.git` 拒绝。
- [ ] 实现临时目录应用和成功后的原子回写。
- [ ] 运行 controller 与 patch service 测试。

### Task 6: 增加前端 Diff 确认卡

**Files:**
- Modify: `demo2/src/main/resources/static/js/tabs/agentscope.js`
- Test: `demo2/src/test/resources/static/agentscope-diff-confirmation-test.md`

**Interfaces:**
- 处理 `WORKSPACE_DIFF` SSE 事件。
- 展示文件列表、增删统计和 unified diff。
- “批准回写”调用 `/agentscope/dev-agent/apply-diff`。
- “拒绝回写”发送相同接口但 `approved=false`。
- 回写期间禁用重复按钮，显示成功、拒绝和冲突结果。

- [ ] 验证 Diff 事件渲染和 HTML 转义。
- [ ] 验证批准请求包含 `diffId`、`sessionId` 和 `userId`。
- [ ] 验证拒绝和基线冲突后的 UI 状态。

### Task 7: 集成验证

**Files:**
- Modify: `demo2/README.md`
- Test: `demo2/src/test/java/com/jason/demo/demo2/agentscope/sandbox/WorkspaceDiffWritebackIntegrationTest.java`

- [ ] 启动 Docker Sandbox 和 demo2。
- [ ] 使用 RetryPolicy 样例在沙箱内修改并运行测试。
- [ ] 确认页面显示真实 unified diff。
- [ ] 拒绝回写，确认宿主 `RetryPolicy.java` 不变。
- [ ] 重新执行并批准回写，确认宿主文件改变。
- [ ] 运行宿主测试确认回写后的代码通过。
- [ ] 验证非沙箱模式和现有工具确认流程未受影响。
