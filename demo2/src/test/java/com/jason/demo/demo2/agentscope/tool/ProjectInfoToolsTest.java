package com.jason.demo.demo2.agentscope.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectInfoToolsTest {

    @TempDir
    Path root;

    @Test
    void readPom_returnsContentAndRespectsMaxChars() throws Exception {
        Files.writeString(
                root.resolve("pom.xml"),
                "<project><java.version>17</java.version></project>",
                StandardCharsets.UTF_8);
        ProjectInfoTools tools = new ProjectInfoTools(root);

        assertThat(tools.readPom(null)).contains("<java.version>17</java.version>");
        assertThat(tools.readPom(10)).hasSize(10);
    }

    @Test
    void listSourceFolders_listsExistingSrcDirs() throws Exception {
        Files.createDirectories(root.resolve("src/main/java"));
        Files.createDirectories(root.resolve("src/test/java"));
        ProjectInfoTools tools = new ProjectInfoTools(root);

        String out = tools.listSourceFolders();
        assertThat(out).contains("src/main/java");
        assertThat(out).contains("src/test/java");
    }

    @Test
    void findMainClass_findsSpringBootApplication() throws Exception {
        Path pkg = root.resolve("src/main/java/com/example");
        Files.createDirectories(pkg);
        Files.writeString(
                pkg.resolve("DemoApp.java"),
                """
                package com.example;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                @SpringBootApplication
                public class DemoApp {}
                """,
                StandardCharsets.UTF_8);
        ProjectInfoTools tools = new ProjectInfoTools(root);

        assertThat(tools.findMainClass()).contains("DemoApp.java");
    }

    @Test
    void readPom_missingFile_returnsClearMessage() {
        ProjectInfoTools tools = new ProjectInfoTools(root);
        assertThat(tools.readPom(null)).containsIgnoringCase("not found");
    }
}
