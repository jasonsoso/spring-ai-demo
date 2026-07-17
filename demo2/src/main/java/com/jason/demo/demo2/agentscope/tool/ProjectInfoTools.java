package com.jason.demo.demo2.agentscope.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class ProjectInfoTools {

    private static final int DEFAULT_MAX_CHARS = 4000;

    private final Path projectRoot;

    public ProjectInfoTools(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    @Tool(
            name = "read_pom",
            description = "Read the current Maven project's pom.xml. Use it when the user asks about dependencies, Java version, Spring Boot version, or project coordinates.",
            readOnly = true)
    public String readPom(
            @ToolParam(
                    name = "max_chars",
                    description = "Maximum characters to return. Default is 4000.",
                    required = false)
            Integer maxChars) {
        Path pom = projectRoot.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return "pom.xml not found under " + projectRoot;
        }
        try {
            String text = Files.readString(pom, StandardCharsets.UTF_8);
            int limit = maxChars == null || maxChars <= 0 ? DEFAULT_MAX_CHARS : maxChars;
            return text.length() <= limit ? text : text.substring(0, limit);
        } catch (IOException e) {
            return "Failed to read pom.xml: " + e.getMessage();
        }
    }

    @Tool(
            name = "list_source_folders",
            description = "List existing source folders in the current project. Use it before answering questions about project layout.",
            readOnly = true)
    public String listSourceFolders() {
        Path src = projectRoot.resolve("src");
        if (!Files.isDirectory(src)) {
            return "src directory not found under " + projectRoot;
        }
        List<String> folders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(src, 3)) {
            walk.filter(Files::isDirectory)
                    .filter(p -> !p.equals(src))
                    .forEach(p -> folders.add(projectRoot.relativize(p).toString().replace('\\', '/')));
        } catch (IOException e) {
            return "Failed to list source folders: " + e.getMessage();
        }
        if (folders.isEmpty()) {
            return "No subfolders under src";
        }
        return String.join("\n", folders);
    }

    @Tool(
            name = "find_main_class",
            description = "Find Java classes annotated with @SpringBootApplication in the current project.",
            readOnly = true)
    public String findMainClass() {
        Path javaRoot = projectRoot.resolve("src/main/java");
        if (!Files.isDirectory(javaRoot)) {
            return "src/main/java not found under " + projectRoot;
        }
        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(javaRoot)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            String text = Files.readString(p, StandardCharsets.UTF_8);
                            if (text.contains("@SpringBootApplication")) {
                                hits.add(projectRoot.relativize(p).toString().replace('\\', '/'));
                            }
                        } catch (IOException ignored) {
                            // skip unreadable file
                        }
                    });
        } catch (IOException e) {
            return "Failed to scan for main class: " + e.getMessage();
        }
        if (hits.isEmpty()) {
            return "No @SpringBootApplication class found";
        }
        return String.join("\n", hits);
    }
}
