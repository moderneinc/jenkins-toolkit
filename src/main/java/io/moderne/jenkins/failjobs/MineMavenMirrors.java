package io.moderne.jenkins.failjobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
public class MineMavenMirrors {
    private final OkHttpClient client;
    private final Path outputDir;
    private final HttpUrl url;
    private final Path script;

    public static void main(String[] args) {
        String base = System.getProperty("url", "https://jenkins.moderne.ninja");
        HttpUrl url = base.endsWith("/") ? HttpUrl.get(base + "scriptText") : HttpUrl.get(base + "/scriptText");
        Path out = Paths.get(System.getProperty("outDir", "jenkins-failed"));
        Path groovyScript = Paths.get("src/jenkins/groovy/fetch-projects.groovy");

        OkHttpClient okHttpClient = new OkHttpClient.Builder().readTimeout(1, TimeUnit.MINUTES).connectTimeout(1, TimeUnit.MINUTES).callTimeout(1, TimeUnit.MINUTES).build();
        new MineMavenMirrors(okHttpClient, out, url, groovyScript).run();
    }

    private String getCrumb(OkHttpClient client, String credential) {
        Request request = new Request.Builder().get().url("https://jenkins.moderne.ninja/crumbIssuer/api/json")
                .header("Authorization", credential).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.error("Unexpected status {}", response);
                throw new IllegalStateException("Unexpected status " + response.code());
            }
            JsonNode responseNode = new ObjectMapper().readValue(Objects.requireNonNull(response.body()).string(), JsonNode.class);
            return responseNode.get("crumb").asText();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void run() {
        try {
            Files.createDirectories(outputDir);
            String scriptText = String.join("\n", Files.readAllLines(script));
            String basicCredential = Credentials.basic("greg@moderne.io", "11f8c789ae3574b09a6bfbbdfb157900e4");
            String crumb = getCrumb(client, basicCredential);
            Call call = client.newCall(new Request.Builder()
                    .post(new FormBody.Builder()
                            .add("script", scriptText)
                            .build())
                    .url(url)
                    .header("Authorization", basicCredential)
                    .header("Jenkins-Crumb", crumb)

                    .build());
            List<JobSummary> summaries = new LinkedList<>();
            try (Response response = call.execute()) {
                ResponseBody body = response.body();
                assert body != null;
                Scanner scanner = new Scanner(body.byteStream());
                while (scanner.hasNextLine()) {
                    String[] parts = scanner.nextLine().split("\t");
                    String jobName = parts[0];
                    String buildNumber = parts[1];
                    String buildTool = parts[2];
                    String status = parts[3];
                    String consoleTextUrl = parts[4];
                    summaries.add(new JobSummary(jobName, buildNumber, buildTool, status, consoleTextUrl));
                }
            }
            log.info("Queuing up fetches for {} failed build(s) to store in {}", summaries.size(), outputDir);
            CountDownLatch latch = new CountDownLatch(summaries.size());
            // repo id to url
            Map<String, String> mirrors = new HashMap<>();

            for (JobSummary s : summaries) {
                boolean isFailure = s.status.equals("failure");
                //Search the gradle logs to determine which version of gradle.
                Request r = new Request.Builder()
                        .get()
                        .url(s.consoleTextUrl())
                        .header("Authorization", basicCredential)
                        .header("Jenkins-Crumb", crumb)
                        .build();
                client.newCall(r).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NotNull Call call, @NotNull IOException e) {
                        log.warn("Failed to call {}", call.request().url(), e);
                        latch.countDown();
                    }

                    @Override
                    public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                        try (ResponseBody body = response.body()) {
                            if (!response.isSuccessful()) {
                                log.warn("Received {} from {}", response.code(), call.request().url());
                            } else if (body == null) {
                                log.warn("Received null body from {}", call.request().url());
                            } else {
                                if (s.jobName().length() < 8) {
                                    log.debug("Skipping " + s.jobName());
                                }
                                // In our example, we're only looking for failures, so skip processing successful job builds
                                if (!isFailure) {
                                    return;
                                }
                                // Based on some predicate, only write output for those failures that match.
                                try (BufferedReader reader = new BufferedReader(new InputStreamReader(body.byteStream()))) {
                                    String line;
                                    while ((line = reader.readLine()) != null) {
                                        // example console log filtering on a message we're looking for
                                        if (line.contains("maven-default-http-blocker") && line.contains("Blocked mirror for repositories")) {
                                            int blockedMirrorIdx = line.indexOf("Blocked mirror");
                                            if (blockedMirrorIdx != -1) {
                                                int repoArrayStartIdx = line.indexOf('[', blockedMirrorIdx);
                                                int repoArrayEndIdx = line.indexOf(']', repoArrayStartIdx);
                                                String reposStr = line.substring(repoArrayStartIdx + 1, repoArrayEndIdx);
                                                // apache.snapshots (http://repository.apache.org/snapshots, default, snapshots), java.net (http://download.java.net/maven/2/, default, releases+snapshots), servicemix.m2 (http://svn.apache.org/repos/asf/servicemix/m2-repo/, default, releases+snapshots), apache.incubating (http://people.apache.org/repo/m2-incubating-repository, default, releases+snapshots), codehaus (http://repository.codehaus.org, default, releases+snapshots)
                                                Matcher m = repoPattern.matcher(reposStr);
                                                while (m.find()) {
                                                    if (m.groupCount() != 4) {
                                                        throw new IllegalStateException("Expected 4 matches in " + reposStr);
                                                    }
                                                    String repoId = m.group(1);
                                                    String repoUrl = m.group(2);
                                                    if (!repoUrl.endsWith("/")) {
                                                        repoUrl += "/";
                                                    }

                                                    String cachedRepoUrl = mirrors.get(repoId);
                                                    if (cachedRepoUrl != null) {
                                                        if (!cachedRepoUrl.equals(repoUrl)) {
                                                            throw new IllegalStateException("mirror conflict, repoId: " + repoId + " already exists with " + cachedRepoUrl + ", which doesn't match " + repoUrl);
                                                        }
                                                    } else {
                                                        mirrors.put(repoId, repoUrl);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } finally {
                            latch.countDown();
                        }
                    }
                });
            }
            boolean completed = latch.await(10, TimeUnit.MINUTES);
            if (completed) {
                log.info("Fetching output complete");
            } else {
                log.warn("Timeout expired while fetching output");
            }
            client.dispatcher().executorService().shutdown();
            for (Map.Entry<String, String> mirror : mirrors.entrySet()) {
                System.out.printf(mirrorTemplate + "%n", "secure-" + mirror.getKey(), mirror.getKey(), mirror.getValue().replaceFirst("http://", "https://"));
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static final Pattern repoPattern = Pattern.compile("\s*(.*?)\s+\\((.*?),\s+(.*?),\s+(.*?)\\),?");

    private static final String mirrorTemplate = """
            <mirror>
                <id>%s</id>
                <mirrorOf>%s</mirrorOf>
                <url>%s</url>
            </mirror>
            """;

    private record JobSummary(String jobName, String buildNumber, String buildTool, String status,
                              String consoleTextUrl) {
    }
}
