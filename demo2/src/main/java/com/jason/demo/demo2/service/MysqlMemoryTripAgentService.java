package com.jason.demo.demo2.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 基于 MySQL 持久化记忆的智能行程规划 Agent。
 */
@Slf4j
@Service
public class MysqlMemoryTripAgentService {

    private static final String SYSTEM_PROMPT = """
            你是生产级智能行程规划 Agent，严格遵守以下规则：
            1. 优先从 MySQL 存储的历史记忆中提取用户偏好（景点类型、饮食禁忌、交通方式、出行人数）；
            2. 无需用户重复说明已存储的偏好，新需求可覆盖旧记忆；
            3. 行程按天/时段拆分，包含景点、交通、餐饮、实用提示（开放时间、预约要求），信息准确可执行；
            4. 语言简洁专业，适配移动端阅读，避免冗余表述；
            5. 未查询到记忆时，按当前需求正常规划，不提示记忆相关信息。
            """;

    private final ChatClient chatClient;
    private final ChatMemory mysqlChatMemory;
    private final JdbcChatMemoryRepository jdbcChatMemoryRepository;
    private final MessageChatMemoryAdvisor messageChatMemoryAdvisor;

    public MysqlMemoryTripAgentService(
            ChatClient.Builder chatClientBuilder,
            @Qualifier("mysqlChatMemory") ChatMemory mysqlChatMemory,
            JdbcChatMemoryRepository jdbcChatMemoryRepository,
            @Qualifier("mysqlMessageChatMemoryAdvisor") MessageChatMemoryAdvisor messageChatMemoryAdvisor) {
        this.chatClient = chatClientBuilder.build();
        this.mysqlChatMemory = mysqlChatMemory;
        this.jdbcChatMemoryRepository = jdbcChatMemoryRepository;
        this.messageChatMemoryAdvisor = messageChatMemoryAdvisor;
    }

    /**
     * 生产级带 MySQL 记忆的行程规划。
     *
     * @param userId     用户唯一标识，即 conversationId
     * @param demand     出行需求
     * @param memoryType 记忆类型（保留 API 兼容，Spring AI 2.0 统一使用 message 模式）
     * @return 个性化行程规划
     */
    public String planTripWithMysqlMemory(String userId, String demand, String memoryType) {
        try {
            return chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(demand)
                    .advisors(messageChatMemoryAdvisor)
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, userId))
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("MySQL 持久化记忆行程规划失败, userId={}, memoryType={}", userId, memoryType, e);
            return "调用 AI 模型失败：" + e.getMessage();
        }
    }

    public void clearUserMemory(String userId) {
        mysqlChatMemory.clear(userId);
    }

    public List<String> listAllConversationIds() {
        return jdbcChatMemoryRepository.findConversationIds();
    }
}
