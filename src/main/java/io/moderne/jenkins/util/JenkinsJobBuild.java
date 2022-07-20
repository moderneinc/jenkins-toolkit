package io.moderne.jenkins.util;

public record JenkinsJobBuild(String jobName, long buildNumber, long timestamp, String result) {
}
