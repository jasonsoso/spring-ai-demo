package com.jason.demo.demo2.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.memory.ChatMemory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AutoMemoryTripAgentServiceTest {

    @TempDir
    Path tempDir;

    private AutoMemoryTripAgentService service;
    private ChatMemory mysqlChatMemory;

    @BeforeEach
    void setUp() {
        mysqlChatMemory = mock(ChatMemory.class);
        service = new AutoMemoryTripAgentService(null, mysqlChatMemory, null, tempDir.toString(), "deepseek-chat", null);
    }

    @Test
    void resolveUserMemoriesRoot_validUserId() {
        Path root = service.resolveUserMemoriesRoot("1001");
        assertEquals(tempDir.resolve("1001").toAbsolutePath().normalize(), root);
    }

    @Test
    void resolveUserMemoriesRoot_rejectsInvalidUserId() {
        assertThrows(IllegalArgumentException.class, () -> service.resolveUserMemoriesRoot("../evil"));
        assertThrows(IllegalArgumentException.class, () -> service.resolveUserMemoriesRoot("user 999"));
        assertThrows(IllegalArgumentException.class, () -> service.resolveUserMemoriesRoot(null));
    }

    @Test
    void clearMemory_deletesDirectoryAndClearsMysql() throws IOException {
        Path userRoot = service.resolveUserMemoriesRoot("1001");
        Files.createDirectories(userRoot);
        Files.writeString(userRoot.resolve("MEMORY.md"), "# index\n");

        service.clearMemory("1001");

        verify(mysqlChatMemory).clear("1001");
        assertFalse(Files.exists(userRoot));
    }

    @Test
    void listMemories_returnsMdFiles() throws IOException {
        Path userRoot = service.resolveUserMemoriesRoot("1001");
        Files.createDirectories(userRoot);
        Files.writeString(userRoot.resolve("MEMORY.md"), "# index\n");
        Files.writeString(userRoot.resolve("user_prefs.md"), "prefs\n");

        Map<String, Object> result = service.listMemories("1001");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) result.get("files");

        assertEquals("1001", result.get("userId"));
        assertEquals(2, files.size());
        assertTrue(files.stream().anyMatch(f -> "MEMORY.md".equals(f.get("name"))));
    }
}
