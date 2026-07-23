package com.jason.demo.demo2.agentscope.model;

/**
 * Dev Agent SSE 推送事件类型。
 */
public enum DevAgentEventType {
    /** 会话开始，携带 sessionId。 */
    SESSION,
    /** 流式文本增量（模型输出片段）。 */
    MESSAGE,
    /** 本次请求正常结束。 */
    DONE,
    /** 发生错误。 */
    ERROR,
    /** Agent 生命周期：开始执行。 */
    AGENT_START,
    /** Agent 生命周期：开始调用模型。 */
    MODEL_CALL_START,
    /** Agent 生命周期：执行结束。 */
    AGENT_END,
    /** 工具调用开始。 */
    TOOL_CALL_START,
    /** 工具调用结束（含成功/失败状态）。 */
    TOOL_RESULT_END,
    /** Agent 最终完整结果。 */
    AGENT_RESULT,
    /** 需要用户确认待执行的工具调用。 */
    REQUIRE_USER_CONFIRM,
    /** 本轮请求停止（如权限询问）。 */
    REQUEST_STOP,
    /** 本轮会话上下文已压缩（摘要替换较早消息）。 */
    COMPACTION
}
