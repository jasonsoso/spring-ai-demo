package com.jason.demo.demo2.agentscope.sandbox;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class MultilineShellFlattenerTest {

    @Test
    void flatten_leavesSingleLineUnchanged() {
        assertThat(MultilineShellFlattener.flatten("mvn -q test")).isEqualTo("mvn -q test");
        assertThat(MultilineShellFlattener.flatten(null)).isNull();
    }

    @Test
    void flatten_encodesMultilineAsSingleLineDecodableScript() {
        String original = "python3 -c \"import sys\\nprint(1)\" 2>&1 <<'__EDIT_EOF__'\n"
                + "cGF5bG9hZA==\n"
                + "__EDIT_EOF__\n";

        String flat = MultilineShellFlattener.flatten(original);

        assertThat(flat).doesNotContain("\n").doesNotContain("\r");
        assertThat(flat).doesNotContain("(").doesNotContain(")").doesNotContain("%");
        assertThat(flat).startsWith("echo ");
        assertThat(flat).endsWith(" | base64 -d > /tmp/as-exec.sh && sh /tmp/as-exec.sh");

        String b64 = flat.substring("echo ".length(), flat.indexOf(" | base64 -d > /tmp/as-exec.sh && sh /tmp/as-exec.sh"));
        String decoded = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo(original.replace("\r\n", "\n").replace('\r', '\n'));
    }
}
