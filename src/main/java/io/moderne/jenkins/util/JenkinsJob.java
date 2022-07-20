package io.moderne.jenkins.util;

import java.util.List;

public record JenkinsJob(String name, String color, List<Long> buildNumbers) {
}
