package com.jason.demo.demo2.agentscope.sandbox;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentscopeSandboxProjectAssetsTest {

    private static final Path MODULE = Path.of(".").toAbsolutePath().normalize();

    @Test
    void retryPolicySampleExistsWithIntentionalBug() throws Exception {
        Path pom = MODULE.resolve("workspace/project/pom.xml");
        Path policy = MODULE.resolve(
                "workspace/project/src/main/java/com/example/retry/RetryPolicy.java");
        Path test = MODULE.resolve(
                "workspace/project/src/test/java/com/example/retry/RetryPolicyTest.java");

        assertThat(pom).exists();
        assertThat(policy).exists();
        assertThat(test).exists();

        String src = Files.readString(policy);
        assertThat(src).contains("1L << attempt");
        assertThat(src).doesNotContain("1L << (attempt - 1)");

        String testSrc = Files.readString(test);
        assertThat(testSrc).contains("assertEquals(1000");
        assertThat(testSrc).contains("assertEquals(2000");
        assertThat(testSrc).contains("assertEquals(4000");
    }
}
