package com.jason.demo.demo2.agentscope.sandbox;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 将含换行的 shell 脚本压成单行后再交给 {@code docker exec ... sh -c}。
 *
 * <p>Windows 上 ProcessBuilder/docker 传参时，带 heredoc 的多行 -c 脚本（AgentScope
 * {@code edit_file}）容易把引号拆坏，导致容器内只执行到 {@code python3 -c import}。
 */
final class MultilineShellFlattener {

    private MultilineShellFlattener() {}

    static String flatten(String command) {
        if (command == null || (command.indexOf('\n') < 0 && command.indexOf('\r') < 0)) {
            return command;
        }
        String normalized = command.replace("\r\n", "\n").replace('\r', '\n');
        String b64 = Base64.getEncoder().encodeToString(normalized.getBytes(StandardCharsets.UTF_8));
        // 单行：无换行、无括号、无 %、无嵌套引号。
        // Windows→docker 传参易拆坏引号；base64 无空白，echo 无需引号。
        return "echo " + b64 + " | base64 -d > /tmp/as-exec.sh && sh /tmp/as-exec.sh";
    }
}
