package io.moderne.jenkins.failjobs;

import io.moderne.jenkins.failjobs.CategorizeFailures;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CategorizeFailuresTest {

    @Test
    void shouldFindExceptions() {
        CategorizeFailures categorize = new CategorizeFailures(Paths.get("src/test/resources/input-dir"));
        List<String> actual = categorize.extract();

        assertThat(firstLineOf(actual.get(0))).isEqualTo("java.lang.InternalError: Could not create SecurityManager: allow");
        assertThat(firstLineOf(actual.get(1))).isEqualTo("org.jenkinsci.plugins.scriptsecurity.sandbox.RejectedAccessException: Scripts not permitted to use new java.lang.IllegalStateException java.lang.String");
        assertThat(firstLineOf(actual.get(2))).isEqualTo("org.gradle.api.tasks.TaskExecutionException: Execution failed for task ':compileSmokeTestJava'.");
        assertThat(firstLineOf(actual.get(3))).isEqualTo("org.gradle.api.tasks.TaskExecutionException: Execution failed for task ':compileTestJava'.");
    }

    @Test
    void shouldGroupExceptions() {
        CategorizeFailures categorize = new CategorizeFailures(Paths.get("src/test/resources/input-dir"));
        List<String> extracted = categorize.extract();
        Map<String, Integer> actual = categorize.group(extracted);
        Map<String, Integer> expected = new HashMap<>();
        expected.put("java.lang.InternalError: Could not create SecurityManager: allow", 1);
        expected.put("org.jenkinsci.plugins.scriptsecurity.sandbox.RejectedAccessException: Scripts not permitted to use new java.lang.IllegalStateException java.lang.String", 1);
        expected.put("org.gradle.api.tasks.TaskExecutionException: Execution failed for task ':compileSmokeTestJava'.", 2);

        assertThat(actual).hasSize(3);
        for (Map.Entry<String, Integer> actualEntry : actual.entrySet()) {
            String exception = actualEntry.getKey();
            String firstLine = firstLineOf(exception);
            Integer expectedCount = expected.get(firstLine);
            assertThat(actualEntry.getValue()).isEqualTo(expectedCount);
        }
    }

    private static String firstLineOf(String in) {
        return in.split("\n")[0];
    }
}
