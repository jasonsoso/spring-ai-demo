package com.jason.demo.demo2.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

/**
 * 带记忆能力的智能行程规划 Agent（DeepSeek 聊天 + MessageChatMemoryAdvisor）
 */
@Slf4j
@Service
public class MemoryTripAgentService {

    private static final String SYSTEM_PROMPT = """
            你是带记忆的智能行程规划 agent，核心规则如下：
            1. 优先从历史对话中提取用户偏好（景点类型、饮食禁忌、交通方式、出行人数等）；
            2. 若用户未重复说明偏好，自动复用记忆中的信息；若有新偏好，覆盖旧记忆；
            3. 行程按天/时段拆分，包含景点、交通、餐饮、实用提示，贴合用户偏好；
            4. 语言简洁明了，结构清晰，无需重复用户已说明的偏好。
            """;

    private final ChatClient.Builder chatClientBuilder;
    private final ChatMemory chatMemory;

    public MemoryTripAgentService(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory) {
        this.chatClientBuilder = chatClientBuilder;
        this.chatMemory = chatMemory;
    }

    /**
     * 带记忆的行程规划：通过 userId 关联用户记忆
     *
     * @param userId     用户唯一标识（隔离不同用户记忆）
     * @param tripDemand 出行需求（可包含新需求或仅查询）
     * @return 个性化行程规划
     */
    public String planTripWithMemory(String userId, String tripDemand) {
        try {
            ChatClient memoryChatClient = chatClientBuilder.clone()
                    .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                    .defaultSystem(SYSTEM_PROMPT)
                    .build();

            return memoryChatClient.prompt()
                    .user(tripDemand)
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, userId))
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("带记忆行程规划失败, userId={}", userId, e);
            return "调用 AI 模型失败：" + e.getMessage();
        }
    }

    /**
     * 清除用户记忆
     */
    public void clearUserMemory(String userId) {
        chatMemory.clear(userId);
    }
}
