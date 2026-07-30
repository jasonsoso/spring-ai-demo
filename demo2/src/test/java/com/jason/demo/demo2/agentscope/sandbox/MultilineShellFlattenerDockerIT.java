package com.jason.demo.demo2.agentscope.sandbox;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 用与 AgentScope DockerSandbox 相同的 ProcessBuilder 传参方式，
 * 验证多行 edit 脚本经 flatten 后在 Windows→docker 下可执行。
 */
class MultilineShellFlattenerDockerIT {

    @Test
    void flatten_editScript_runsViaDockerExecProcessBuilder() throws Exception {
        assumeTrue(dockerAvailable(), "docker not available");

        String payloadB64 = Base64.getEncoder().encodeToString(
                "{\"path\":\"/tmp/t.txt\",\"old\":\"FOO\",\"new\":\"BAR\",\"replace_all\":false}"
                        .getBytes(StandardCharsets.UTF_8));
        String edit = """
                python3 -c "import sys, os, base64, json\\npayload = json.loads(base64.b64decode(sys.stdin.read().strip()).decode('utf-8'))\\npath, old, new = payload['path'], payload['old'], payload['new']\\nreplace_all = payload.get('replace_all', False)\\nif not os.path.isfile(path):\\n    print(json.dumps({'error': 'file_not_found'}))\\n    sys.exit(0)\\nwith open(path, 'rb') as f: text = f.read().decode('utf-8')\\ncount = text.count(old)\\nif count == 0:\\n    print(json.dumps({'error': 'string_not_found'}))\\n    sys.exit(0)\\nif count > 1 and not replace_all:\\n    print(json.dumps({'error': 'multiple_occurrences', 'count': count}))\\n    sys.exit(0)\\nresult = text.replace(old, new) if replace_all else text.replace(old, new, 1)\\nwith open(path, 'wb') as f: f.write(result.encode('utf-8'))\\nprint(json.dumps({'count': count}))\\n" 2>&1 <<'__EDIT_EOF__'
                %s
                __EDIT_EOF__
                """.formatted(payloadB64).replace("\r\n", "\n");

        String flat = MultilineShellFlattener.flatten(edit);
        assertThat(flat).doesNotContain("\n");

        String cid = runCapture("docker", "run", "-d", "--entrypoint", "sleep",
                "agentscope-java-sandbox:17", "60").trim();
        try {
            runCapture("docker", "exec", cid, "sh", "-c", "echo FOO > /tmp/t.txt");
            String out = runCapture("docker", "exec", cid, "sh", "-c", flat);
            String file = runCapture("docker", "exec", cid, "cat", "/tmp/t.txt").trim();
            assertThat(out).contains("\"count\"");
            assertThat(file).isEqualTo("BAR");
        } finally {
            new ProcessBuilder("docker", "rm", "-f", cid).start().waitFor(30, TimeUnit.SECONDS);
        }
    }

    private static boolean dockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "image", "inspect", "agentscope-java-sandbox:17")
                    .redirectErrorStream(true)
                    .start();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String runCapture(String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = p.waitFor(60, TimeUnit.SECONDS);
        assertThat(finished).as("command timed out: %s", String.join(" ", command)).isTrue();
        assertThat(p.exitValue())
                .as("command failed (%s): %s", String.join(" ", command), out)
                .isZero();
        return out;
    }
}
