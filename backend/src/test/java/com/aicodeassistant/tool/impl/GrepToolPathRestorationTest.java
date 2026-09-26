package com.aicodeassistant.tool.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GrepToolPathRestorationTest {

    @Test
    void restoresForwardSlashOutputWithoutChangingMatchText() {
        String root = Path.of("search-root").toAbsolutePath().toString();
        String output = "./src/A.java\n./src/A.java:12:./literal .\\literal\n"
                + "./src/A.java-13-context\n--\ndiagnostic";

        assertThat(restore(output, root)).isEqualTo(
                root + File.separator + "src/A.java\n"
                        + root + File.separator + "src/A.java:12:./literal .\\literal\n"
                        + root + File.separator + "src/A.java-13-context\n--\ndiagnostic");
    }

    @Test
    void doesNotDuplicateTrailingSeparatorOrChangeEmptyOutput() {
        String root = Path.of("search-root").toAbsolutePath() + File.separator;

        assertThat(restore("./A.java", root)).isEqualTo(root + "A.java");
        assertThat(restore("", root)).isEmpty();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void restoresWindowsPrefixesForDriveAndUncRoots() {
        for (String root : new String[]{"C:\\work\\repo", "C:\\", "\\\\server\\share\\"}) {
            String prefix = root.endsWith("\\") ? root : root + "\\";
            assertThat(restore(".\\src\\A.java\n.\\src\\A.java:2:match", root))
                    .isEqualTo(prefix + "src\\A.java\n" + prefix + "src\\A.java:2:match");
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void preservesLiteralBackslashesOnUnix() {
        assertThat(restore(".\\literal", "/repo")).isEqualTo(".\\literal");
        assertThat(restore("./A.java", "/repo\\")).isEqualTo("/repo\\/A.java");
        assertThat(restore("./A.java", "/")).isEqualTo("/A.java");
    }

    private static String restore(String output, String root) {
        return ReflectionTestUtils.invokeMethod(
                GrepTool.class, "restoreSearchRootPaths", output, root);
    }
}
