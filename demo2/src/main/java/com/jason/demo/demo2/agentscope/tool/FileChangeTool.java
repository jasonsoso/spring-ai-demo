package com.jason.demo.demo2.agentscope.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class FileChangeTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(FileChangeTool.class);

    private static final Set<String> WRITE_OPERATIONS =
            Set.of("create", "write", "update", "append");

    private final Path projectRoot;
    private final Path notesRoot;

    public FileChangeTool(Path projectRoot) {
        super(ToolBase.builder()
                .name("request_file_change")
                .description(
                        "Request a file create/write under the notes/ directory. "
                                + "Only use when the user explicitly asks to write a file. "
                                + "Deletes are not allowed. Paths outside notes/ are rejected.")
                .inputSchema(inputSchema())
                .readOnly(false)
                .concurrencySafe(false));
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.notesRoot = this.projectRoot.resolve("notes").normalize();
    }

    private static Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(
                "operation",
                Map.of(
                        "type", "string",
                        "description", "File operation: create, write, update, or append. delete is denied."));
        properties.put(
                "path",
                Map.of(
                        "type", "string",
                        "description", "Relative path under notes/, e.g. notes/plan.txt"));
        properties.put(
                "content",
                Map.of(
                        "type", "string",
                        "description", "Text content to write."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("operation", "path", "content"));
        return schema;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContextState context) {
        log.info("Checking file change permission");
        String operation = value(toolInput, "operation").toLowerCase(Locale.ROOT);
        String path = value(toolInput, "path");

        if ("delete".equals(operation) || "remove".equals(operation)) {
            return Mono.just(PermissionDecision.builder()
                    .behavior(PermissionBehavior.DENY)
                    .message("删除文件不允许由 Agent 自动执行：" + path)
                    .decisionReason("safety: delete operation is denied")
                    .build());
        }

        if (!WRITE_OPERATIONS.contains(operation)) {
            return Mono.just(PermissionDecision.builder()
                    .behavior(PermissionBehavior.DENY)
                    .message("不支持的文件操作：" + operation)
                    .decisionReason("safety: unsupported file operation")
                    .build());
        }

        try {
            resolveTarget(path);
            return Mono.just(PermissionDecision.builder()
                    .behavior(PermissionBehavior.ASK)
                    .message("写入类操作需要人工确认：" + path)
                    .decisionReason("safety: write operation requires approval")
                    .build());
        } catch (IllegalArgumentException ex) {
            return Mono.just(PermissionDecision.builder()
                    .behavior(PermissionBehavior.DENY)
                    .message(ex.getMessage())
                    .decisionReason("safety: path is outside notes directory")
                    .build());
        }
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            Map<String, Object> input = param.getInput();
            String operation = value(input, "operation").toLowerCase(Locale.ROOT);
            String path = value(input, "path");
            String content = value(input, "content");

            log.info("Executing confirmed file change operation={} path={}", operation, path);
            Path target = resolveTarget(path);
            ensureNoSymlinks(target);
            Files.createDirectories(target.getParent());
            if ("append".equals(operation) && Files.isRegularFile(target)) {
                Files.writeString(
                        target,
                        content,
                        StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.APPEND);
            } else {
                Files.writeString(target, content, StandardCharsets.UTF_8);
            }
            log.info("File change completed path={}", path);
            return ToolResultBlock.text("已写入文件：" + path + "（operation=" + operation + "）");
        }).onErrorResume(ex -> Mono.just(ToolResultBlock.error(
                ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage())));
    }

    Path resolveTarget(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        Path relative = Path.of(path);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("不允许绝对路径：" + path);
        }
        Path resolved = projectRoot.resolve(relative).normalize();
        if (!resolved.startsWith(notesRoot)) {
            throw new IllegalArgumentException("路径必须位于 notes/ 目录下：" + path);
        }
        return resolved;
    }

    private void ensureNoSymlinks(Path target) throws java.io.IOException {
        if (Files.exists(notesRoot) && Files.isSymbolicLink(notesRoot)) {
            throw new IllegalArgumentException("notes/ 不能是符号链接");
        }
        Path parent = target.getParent();
        if (parent != null && Files.exists(parent) && Files.isSymbolicLink(parent)) {
            throw new IllegalArgumentException("目标父目录不能是符号链接：" + parent);
        }
        if (Files.exists(target) && Files.isSymbolicLink(target)) {
            throw new IllegalArgumentException("目标文件不能是符号链接：" + target);
        }
    }

    private static String value(Map<String, Object> input, String key) {
        if (input == null || input.get(key) == null) {
            return "";
        }
        return String.valueOf(input.get(key));
    }
}
