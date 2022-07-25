package io.moderne.jenkins.failjobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
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
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@RequiredArgsConstructor
public class MineJobsData {
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
        new MineJobsData(okHttpClient, out, url, groovyScript).run(args);
    }

    private String getCrumb(OkHttpClient client, String credential) {
        Request request = new Request.Builder().get().url("https://jenkins.moderne.ninja/crumbIssuer/api/json")
                .header("Authorization", credential).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.error("Unexpected status {}", response);
                throw new IllegalStateException("Unexpected status " + response.code());
            }
            JsonNode responseNode = new ObjectMapper().readValue(response.body().string(), JsonNode.class);
            return responseNode.get("crumb").asText();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void run(String[] args) {
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
            Map<Integer, AtomicInteger> versionMap = new HashMap<>();
            int totalProjects = 0;
            int totalProjectsGradle = 0;
            int totalProjectsMaven = 0;
            int mavenFailures = 0;
            int gradleFailures = 0;

            for (JobSummary s : summaries) {
                boolean isFailure = s.status.equals("failure");
                totalProjects++;
                if ("gradle".equals(s.buildTool)) {
                    totalProjectsGradle++;
                    if (isFailure) {
                        gradleFailures++;
                    }
                } else if ("maven".equals(s.buildTool)) {
                    totalProjectsMaven++;
                    if (isFailure) {
                        mavenFailures++;
                    }
                }
                if ("maven".equals(s.buildTool)) {
                    latch.countDown();
                    continue;
                }
                //Search the gradle logs to determine which version of gradle.
                Request r = new Request.Builder()
                        .get()
                        .url(s.getConsoleTextUrl())
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
                                //BASED ON SOME PREDICATE, only write output for those failures that match.
                                try (BufferedReader reader = new BufferedReader(new InputStreamReader(body.byteStream()))) {
                                    String line = reader.readLine();
                                    while (line != null) {
                                        if (line.startsWith("Downloading https://services.gradle.org/distributions/gradle-")) {
                                            String jobName = s.getJobName().substring(6).replaceFirst("_", "/");
                                            Integer gradleMajor = Integer.parseInt(line.substring(61, line.indexOf('.', 61)));

                                            versionMap.computeIfAbsent(gradleMajor, k -> new AtomicInteger(0)).incrementAndGet();
                                            System.out.println(jobName + " - Gradle Version " + gradleMajor + ".x");
                                            break;
                                        }
                                        line = reader.readLine();
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
            System.out.println("Total Projects: " + totalProjects);
            System.out.println("Total Maven Projects: " + totalProjectsMaven);
            System.out.println("       Maven Failures: " + mavenFailures);
            System.out.println("Total Gradle Projects: " + totalProjectsGradle);
            System.out.println("      Gradle Failures: " + gradleFailures);
            System.out.println("Project counts by gradle version:");
            versionMap.forEach((key, value) -> System.out.println("Gradle Version " + key + " project count = " + value));
            client.dispatcher().executorService().shutdown();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Data
    private static class JobSummary {
        private final String jobName;
        private final String buildNumber;
        private final String buildTool;
        private final String status;
        private final String consoleTextUrl;
    }
}
