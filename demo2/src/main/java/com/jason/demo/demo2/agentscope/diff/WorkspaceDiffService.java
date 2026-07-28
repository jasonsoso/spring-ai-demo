package com.jason.demo.demo2.agentscope.diff;

import com.jason.demo.demo2.agentscope.config.DevAgentProperties;
import com.jason.demo.demo2.agentscope.model.WorkspaceDiff;
import com.jason.demo.demo2.agentscope.model.WorkspaceFileDiff;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Service
public class WorkspaceDiffService {

    private final Path projectRoot;
    private final LocalSnapshotSpec snapshotSpec;
    private final Map<String, Map<String, String>> baselines = new ConcurrentHashMap<>();
    private final Map<String, List<String>> baselineSnapshots = new ConcurrentHashMap<>();
    private final Map<String, WorkspaceDiff> pending = new ConcurrentHashMap<>();

    public WorkspaceDiffService(DevAgentProperties properties) {
        this.projectRoot = Path.of(properties.projectRoot(), "workspace", "project")
                .toAbsolutePath().normalize();
        Path snapshotRoot = Path.of(properties.projectRoot())
                .resolve(properties.sandbox().snapshotRoot()).toAbsolutePath().normalize();
        this.snapshotSpec = new LocalSnapshotSpec(snapshotRoot);
    }

    public void captureBaseline(String userId, String sessionId) {
        String key = key(userId, sessionId);
        baselines.put(key, snapshotFiles(projectRoot));
        baselineSnapshots.put(key, snapshotIds());
    }

    public WorkspaceDiff createDiff(String userId, String sessionId) {
        String key = key(userId, sessionId);
        Map<String, String> before = baselines.get(key);
        if (before == null) {
            return null;
        }
        Path snapshotProject = restoreProject(key);
        if (snapshotProject == null) {
            return null;
        }
        Map<String, String> after = snapshotFiles(snapshotProject);
        List<WorkspaceFileDiff> files = new ArrayList<>();
        StringBuilder unified = new StringBuilder();
        before.keySet().stream().sorted().forEach(path -> {
            String oldHash = before.get(path);
            String newHash = after.get(path);
            if (newHash == null) {
                files.add(new WorkspaceFileDiff(path, "DELETED", 0, 1, oldHash, null, null));
                unified.append("--- a/").append(path).append('\n')
                        .append("+++ /dev/null\n");
            } else if (!oldHash.equals(newHash)) {
                addChangedFile(files, unified, path, oldHash, newHash, projectRoot.resolve(path), snapshotProject.resolve(path));
            }
        });
        after.keySet().stream().filter(path -> !before.containsKey(path)).sorted().forEach(path -> {
            String content = read(snapshotProject.resolve(path));
            files.add(new WorkspaceFileDiff(
                    path, "ADDED", countLines(content), 0, null, after.get(path), content));
            unified.append("--- /dev/null\n+++ b/").append(path).append('\n')
                    .append("@@ added @@\n").append(prefixLines("+", content));
        });
        if (files.isEmpty()) {
            return null;
        }
        WorkspaceDiff diff = new WorkspaceDiff(
                UUID.randomUUID().toString(), userId, sessionId, key, List.copyOf(files), unified.toString());
        pending.put(diff.diffId(), diff);
        return diff;
    }

    public WorkspaceDiff getPending(String diffId) {
        return pending.get(diffId);
    }

    public void discard(String diffId) {
        pending.remove(diffId);
    }

    public void apply(String userId, String sessionId, String diffId) {
        WorkspaceDiff diff = pending.get(diffId);
        if (diff == null || !userId.equals(diff.userId()) || !sessionId.equals(diff.sessionId())) {
            throw new IllegalArgumentException("未知或不匹配的 workspace diff");
        }
        Map<String, String> baseline = baselines.get(key(userId, sessionId));
        for (WorkspaceFileDiff file : diff.files()) {
            Path target = requireProjectPath(file.path());
            String currentHash = Files.exists(target) ? hash(target) : null;
            if (!java.util.Objects.equals(currentHash, baseline.get(file.path()))) {
                throw new IllegalStateException("宿主文件已变化，拒绝回写: " + file.path());
            }
        }
        Map<Path, byte[]> backups = new HashMap<>();
        for (WorkspaceFileDiff file : diff.files()) {
            Path target = requireProjectPath(file.path());
            try {
                if (Files.exists(target)) {
                    backups.put(target, Files.readAllBytes(target));
                }
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("读取回写备份失败: " + file.path(), ex);
            }
        }
        try {
            for (WorkspaceFileDiff file : diff.files()) {
                Path target = requireProjectPath(file.path());
                if ("DELETED".equals(file.changeType())) {
                    Files.deleteIfExists(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, file.newContent(), StandardCharsets.UTF_8);
                }
            }
        } catch (java.io.IOException | RuntimeException ex) {
            rollback(backups, diff);
            throw new IllegalStateException("回写失败，已回滚", ex);
        }
        pending.remove(diffId);
    }

    private void rollback(Map<Path, byte[]> backups, WorkspaceDiff diff) {
        for (WorkspaceFileDiff file : diff.files()) {
            Path target = requireProjectPath(file.path());
            try {
                byte[] original = backups.get(target);
                if (original == null) {
                    Files.deleteIfExists(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.write(target, original);
                }
            } catch (java.io.IOException ignored) {
                // Preserve the original failure; the next apply attempt will be blocked by hash validation.
            }
        }
    }

    private void addChangedFile(
            List<WorkspaceFileDiff> files,
            StringBuilder unified,
            String path,
            String oldHash,
            String newHash,
            Path oldPath,
            Path newPath) {
        String oldContent = read(oldPath);
        String newContent = read(newPath);
        LineDiff lineDiff = lineDiff(oldContent, newContent);
        files.add(new WorkspaceFileDiff(
                path, "MODIFIED", lineDiff.additions(), lineDiff.deletions(), oldHash, newHash, newContent));
        unified.append("--- a/").append(path).append('\n')
                .append("+++ b/").append(path).append('\n')
                .append(lineDiff.unified());
    }

    private static LineDiff lineDiff(String oldContent, String newContent) {
        List<String> oldLines = lines(oldContent);
        List<String> newLines = lines(newContent);
        int oldSize = oldLines.size();
        int newSize = newLines.size();
        int[][] lcs = new int[oldSize + 1][newSize + 1];
        for (int oldIndex = oldSize - 1; oldIndex >= 0; oldIndex--) {
            for (int newIndex = newSize - 1; newIndex >= 0; newIndex--) {
                lcs[oldIndex][newIndex] = oldLines.get(oldIndex).equals(newLines.get(newIndex))
                        ? lcs[oldIndex + 1][newIndex + 1] + 1
                        : Math.max(lcs[oldIndex + 1][newIndex], lcs[oldIndex][newIndex + 1]);
            }
        }

        List<DiffLine> operations = new ArrayList<>();
        int oldIndex = 0;
        int newIndex = 0;
        int oldLine = 1;
        int newLine = 1;
        while (oldIndex < oldSize || newIndex < newSize) {
            if (oldIndex < oldSize && newIndex < newSize
                    && oldLines.get(oldIndex).equals(newLines.get(newIndex))) {
                operations.add(new DiffLine(' ', oldLines.get(oldIndex), oldLine, newLine, false));
                oldIndex++;
                newIndex++;
                oldLine++;
                newLine++;
            } else if (newIndex < newSize
                    && (oldIndex == oldSize || lcs[oldIndex][newIndex + 1] >= lcs[oldIndex + 1][newIndex])) {
                operations.add(new DiffLine('+', newLines.get(newIndex), oldLine, newLine, true));
                newIndex++;
                newLine++;
            } else {
                operations.add(new DiffLine('-', oldLines.get(oldIndex), oldLine, newLine, true));
                oldIndex++;
                oldLine++;
            }
        }

        List<Integer> changed = new ArrayList<>();
        for (int index = 0; index < operations.size(); index++) {
            if (operations.get(index).changed()) {
                changed.add(index);
            }
        }
        if (changed.isEmpty()) {
            return new LineDiff("", 0, 0);
        }
        int start = Math.max(0, changed.get(0) - 3);
        int end = Math.min(operations.size() - 1, changed.get(changed.size() - 1) + 3);
        List<DiffLine> hunk = operations.subList(start, end + 1);
        int oldStart = hunk.get(0).oldLine();
        int newStart = hunk.get(0).newLine();
        int deletions = (int) hunk.stream().filter(line -> line.prefix() == '-').count();
        int additions = (int) hunk.stream().filter(line -> line.prefix() == '+').count();
        StringBuilder output = new StringBuilder("@@ -")
                .append(oldStart).append(',').append(Math.max(1, oldLineCount(hunk)))
                .append(" +").append(newStart).append(',').append(Math.max(1, newLineCount(hunk)))
                .append(" @@\n");
        hunk.forEach(line -> output.append(line.prefix()).append(line.text()).append('\n'));
        return new LineDiff(output.toString(), additions, deletions);
    }

    private static int oldLineCount(List<DiffLine> lines) {
        return (int) lines.stream().filter(line -> line.prefix() != '+').count();
    }

    private static int newLineCount(List<DiffLine> lines) {
        return (int) lines.stream().filter(line -> line.prefix() != '-').count();
    }

    private static List<String> lines(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        return content.replace("\r\n", "\n").replace('\r', '\n').lines().toList();
    }

    private record DiffLine(
            char prefix,
            String text,
            int oldLine,
            int newLine,
            boolean changed) {
    }

    private record LineDiff(String unified, int additions, int deletions) {
    }

    private Path restoreProject(String sessionKey) {
        try {
            List<String> known = baselineSnapshots.getOrDefault(sessionKey, List.of());
            String snapshotId = snapshotIds().stream()
                    .filter(id -> !known.contains(id))
                    .max(Comparator.comparing(this::snapshotModifiedTime))
                    .orElse(null);
            if (snapshotId == null) {
                return null;
            }
            SandboxSnapshot snapshot = snapshotSpec.build(snapshotId);
            if (!snapshot.isRestorable()) {
                return null;
            }
            Path extracted = Files.createTempDirectory("agentscope-diff-");
            Path archive = extracted.resolve("snapshot.tar");
            try (InputStream input = snapshot.restore()) {
                Files.copy(input, archive);
            }
            Process process = new ProcessBuilder(
                    "tar", "-xf", archive.toString(), "-C", extracted.toString())
                    .redirectErrorStream(true).start();
            if (process.waitFor() != 0) {
                return null;
            }
            Path direct = extracted.resolve("workspace").resolve("project");
            if (Files.isDirectory(direct)) {
                return direct;
            }
            direct = extracted.resolve("project");
            return Files.isDirectory(direct) ? direct : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private List<String> snapshotIds() {
        try {
            if (!Files.isDirectory(snapshotSpec.getBasePath() == null
                    ? Path.of(".") : Path.of(snapshotSpec.getBasePath()))) {
                return List.of();
            }
            try (Stream<Path> paths = Files.list(Path.of(snapshotSpec.getBasePath()))) {
                return paths.filter(path -> path.getFileName().toString().endsWith(".tar"))
                        .map(path -> path.getFileName().toString()
                                .substring(0, path.getFileName().toString().length() - 4))
                        .toList();
            }
        } catch (java.io.IOException ex) {
            return List.of();
        }
    }

    private long snapshotModifiedTime(String id) {
        try {
            return Files.getLastModifiedTime(Path.of(snapshotSpec.getBasePath(), id + ".tar")).toMillis();
        } catch (java.io.IOException ex) {
            return Long.MIN_VALUE;
        }
    }

    private Map<String, String> snapshotFiles(Path root) {
        Map<String, String> result = new HashMap<>();
        if (!Files.isDirectory(root)) {
            return result;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .forEach(path -> {
                        String relative = root.relativize(path).toString().replace('\\', '/');
                        if (!relative.startsWith("target/")
                                && !relative.startsWith(".git/")
                                && !relative.startsWith(".agentscope/")) {
                            result.put(relative, hash(path));
                        }
                    });
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("读取 workspace 失败", ex);
        }
        return result;
    }

    private Path requireProjectPath(String relative) {
        Path path = projectRoot.resolve(relative).normalize();
        if (!path.startsWith(projectRoot) || relative.startsWith("/") || relative.contains("..")) {
            throw new IllegalArgumentException("非法 workspace 路径: " + relative);
        }
        return path;
    }

    private static String key(String userId, String sessionId) {
        return (userId == null ? "" : userId) + "/" + sessionId;
    }

    private static String hash(Path path) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (Exception ex) {
            throw new IllegalStateException("计算文件 hash 失败", ex);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("读取文件失败: " + path, ex);
        }
    }

    private static int countLines(String content) {
        return content == null || content.isEmpty() ? 0 : content.split("\\R", -1).length;
    }

    private static String prefixLines(String prefix, String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        return content.replace("\r\n", "\n").lines()
                .map(line -> prefix + line + "\n")
                .collect(java.util.stream.Collectors.joining());
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }
}
