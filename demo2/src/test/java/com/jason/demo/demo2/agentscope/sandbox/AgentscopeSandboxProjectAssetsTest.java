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

    @Test
    void sandboxDockerAssetsExistWithUsageComments() throws Exception {
        Path dockerfile = MODULE.resolve("docker/sandbox/Dockerfile");
        Path wrapper = MODULE.resolve("docker/sandbox/python3-wrapper");
        Path compose = MODULE.resolve("docker/sandbox/docker-compose.yml");

        assertThat(dockerfile).exists();
        assertThat(wrapper).exists();
        assertThat(compose).exists();

        String composeText = Files.readString(compose);
        assertThat(composeText).contains("agentscope-java-sandbox:17");
        assertThat(composeText).contains("docker compose -f demo2/docker/sandbox/docker-compose.yml build");
        assertThat(composeText).contains("不要对本文件 up -d");
        assertThat(composeText).contains("sandbox.enabled=true");
        assertThat(composeText).contains("agentscope-postgres");

        String dockerfileText = Files.readString(dockerfile);
        assertThat(dockerfileText).contains("python3-wrapper");
        assertThat(dockerfileText).contains("maven.test.failure.ignore");

        String wrapperText = Files.readString(wrapper);
        assertThat(wrapperText).contains("printf '%b'");
        assertThat(wrapperText).contains("b64decode");
        assertThat(wrapperText).contains("mktemp");
    }

    @Test
    void agentsMdContainsSandboxRouting() throws Exception {
        String agents = Files.readString(MODULE.resolve("workspace/AGENTS.md"));
        assertThat(agents).contains("RetryPolicy");
        assertThat(agents).contains("read_file");
        assertThat(agents).contains("edit_file");
        assertThat(agents).contains("execute");
        assertThat(agents).contains("/workspace/project");
    }
}
