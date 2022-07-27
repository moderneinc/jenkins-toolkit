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
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
            JsonNode responseNode = new ObjectMapper().readValue(Objects.requireNonNull(response.body()).string(), JsonNode.class);
            return responseNode.get("crumb").asText();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private record ErrorFoundAndGradleVersion(boolean errorFound, String gradleVersion) {
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

            for (JobSummary s : summaries) {
                boolean isFailure = s.status.equals("failure");
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
                                String jobName = s.getJobName().substring(7).replaceFirst("_", "/");
                                // In our example, we're only looking for failures, so skip processing successful job builds
                                if (!isFailure) {
                                    return;
                                }
                                // Based on some predicate, only write output for those failures that match.
                                try (BufferedReader reader = new BufferedReader(new InputStreamReader(body.byteStream()))) {
                                    String line;
                                    while ((line = reader.readLine()) != null) {
                                        // example console log filtering on a message we're looking for
                                        if (line.contains("maven-default-http-blocker")) {
                                            // output in a format suitable for pasting into a literal map in RepoCsvBatch
                                            System.out.println("\"" + jobName + "\",");
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
