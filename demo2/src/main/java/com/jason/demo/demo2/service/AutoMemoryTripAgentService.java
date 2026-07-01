package com.jason.demo.demo2.service;

import com.jason.demo.demo2.config.AutoMemoryAgentConfig;
import com.jason.demo.demo2.config.AutoMemoryAgentConfig.AutoMemoryToolCallingTrigger;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@Service
public class AutoMemoryTripAgentService {

    private static final Pattern USER_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");

    private static final String SYSTEM_PROMPT = """
            你是带双层记忆的智能行程规划 Agent，严格遵守以下规则：
            1. 优先从长期记忆 Markdown 文件（MEMORY.md 及关联文件）提取用户偏好（景点类型、饮食禁忌、交通方式、出行人数等）；
            2. 结合 MySQL 会话历史补全当前对话上下文，无需用户重复已存储的偏好；
            3. 当学到值得跨会话保留的新偏好或反馈时，使用记忆工具持久化到长期记忆；
            4. 行程按天/时段拆分，包含景点、交通、餐饮、实用提示，贴合用户偏好；
            5. 语言简洁明了，结构清晰。
            """;

    private final ChatClient.Builder chatClientBuilder;
    private final ChatMemory mysqlChatMemory;
    private final MessageChatMemoryAdvisor mysqlMessageChatMemoryAdvisor;
    private final String agentMemoriesDir;
    private final String autoMemoryChatModel;
    private final AutoMemoryToolCallingTrigger toolCallingTrigger;
    private final ConcurrentHashMap<String, AutoMemoryToolsAdvisor> advisorCache = new ConcurrentHashMap<>();

    @Autowired
    public AutoMemoryTripAgentService(
            ChatClient.Builder chatClientBuilder,
            @Qualifier("mysqlChatMemory") ChatMemory mysqlChatMemory,
            @Qualifier("mysqlMessageChatMemoryAdvisor") MessageChatMemoryAdvisor mysqlMessageChatMemoryAdvisor,
            AutoMemoryAgentConfig autoMemoryAgentConfig,
            AutoMemoryToolCallingTrigger toolCallingTrigger) {
        this(chatClientBuilder, mysqlChatMemory, mysqlMessageChatMemoryAdvisor,
                autoMemoryAgentConfig.getAgentMemoriesDir(),
                autoMemoryAgentConfig.getAutoMemoryChatModel(),
                toolCallingTrigger);
    }

    AutoMemoryTripAgentService(
            ChatClient.Builder chatClientBuilder,
            ChatMemory mysqlChatMemory,
            MessageChatMemoryAdvisor mysqlMessageChatMemoryAdvisor,
            String agentMemoriesDir,
            String autoMemoryChatModel,
            AutoMemoryToolCallingTrigger toolCallingTrigger) {
        this.chatClientBuilder = chatClientBuilder;
        this.mysqlChatMemory = mysqlChatMemory;
        this.mysqlMessageChatMemoryAdvisor = mysqlMessageChatMemoryAdvisor;
        this.agentMemoriesDir = agentMemoriesDir;
        this.autoMemoryChatModel = autoMemoryChatModel;
        this.toolCallingTrigger = toolCallingTrigger;
    }

    public String chat(String userId, String message) {
        try {
            AutoMemoryToolsAdvisor longTermAdvisor = advisorFor(userId);
            ChatClient client = chatClientBuilder.clone()
                    .defaultOptions(DeepSeekChatOptions.builder().model(autoMemoryChatModel))
                    .defaultTools(toolCallingTrigger)
                    .defaultAdvisors(
                            longTermAdvisor,
                            ToolCallingAdvisor.builder().build(),
                            mysqlMessageChatMemoryAdvisor)
                    .defaultSystem(SYSTEM_PROMPT)
                    .build();
            return client.prompt()
                    .user(message)
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, userId))
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("AutoMemory 对话失败, userId={}", userId, e);
            return "调用 AI 模型失败：" + e.getMessage();
        }
    }

    public Map<String, Object> listMemories(String userId) {
        Path userRoot = resolveUserMemoriesRoot(userId);
        List<Map<String, Object>> files = new ArrayList<>();
        if (Files.isDirectory(userRoot)) {
            try (Stream<Path> walk = Files.walk(userRoot)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".md"))
                        .sorted(Comparator.comparing(path -> userRoot.relativize(path).toString()))
                        .forEach(path -> {
                            try {
                                Map<String, Object> file = new LinkedHashMap<>();
                                Path relative = userRoot.relativize(path);
                                file.put("name", path.getFileName().toString());
                                file.put("relativePath", relative.toString().replace('\\', '/'));
                                file.put("sizeBytes", Files.size(path));
                                files.add(file);
                            } catch (IOException e) {
                                log.warn("读取记忆文件大小失败: {}", path, e);
                            }
                        });
            } catch (IOException e) {
                log.error("列出记忆文件失败, userId={}", userId, e);
                throw new IllegalStateException("列出记忆文件失败：" + e.getMessage(), e);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("memoriesRoot", userRoot.toString());
        result.put("files", files);
        return result;
    }

    public void clearMemory(String userId) {
        mysqlChatMemory.clear(userId);
        advisorCache.remove(userId);
        Path userRoot = resolveUserMemoriesRoot(userId);
        if (!Files.exists(userRoot)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(userRoot)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("删除记忆目录失败：" + path, e);
                }
            });
        } catch (IOException e) {
            log.error("清除长期记忆目录失败, userId={}", userId, e);
            throw new IllegalStateException("清除长期记忆失败：" + e.getMessage(), e);
        }
    }

    Path resolveUserMemoriesRoot(String userId) {
        if (userId == null || !USER_ID_PATTERN.matcher(userId).matches()) {
            throw new IllegalArgumentException("userId 仅允许字母、数字、下划线与连字符");
        }
        Path base = Path.of(agentMemoriesDir).normalize().toAbsolutePath();
        Path userRoot = base.resolve(userId).normalize();
        if (!userRoot.startsWith(base)) {
            throw new IllegalArgumentException("非法 userId");
        }
        return userRoot;
    }

    private AutoMemoryToolsAdvisor advisorFor(String userId) {
        return advisorCache.computeIfAbsent(userId, id ->
                AutoMemoryToolsAdvisor.builder()
                        .memoriesRootDirectory(resolveUserMemoriesRoot(id).toString())
                        .build());
    }
}
