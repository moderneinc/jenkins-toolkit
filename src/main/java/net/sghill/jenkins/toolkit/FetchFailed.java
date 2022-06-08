package net.sghill.jenkins.toolkit;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class FetchFailed {
    private final OkHttpClient client;
    private final Path outputDir;
    private final HttpUrl url;
    private final Path script;

    public static void main(String[] args) {
        String base = System.getProperty("url", "http://localhost:8080");
        HttpUrl url = base.endsWith("/") ? HttpUrl.get(base + "scriptText") : HttpUrl.get(base + "/scriptText");
        Path out = Paths.get(System.getProperty("outDir", "out"));
        Path groovyScript = Paths.get(System.getProperty("script"));
        new FetchFailed(new OkHttpClient(), out, url, groovyScript).run(args);
    }

    public void run(String[] args) {
        try {
            Files.createDirectories(outputDir);
            String scriptText = String.join("\n", Files.readAllLines(script));
            Call call = client.newCall(new Request.Builder()
                    .post(new FormBody.Builder()
                            .add("script", scriptText)
                            .build())
                    .url(url)
                    .build());
            List<Failed> failed = new LinkedList<>();
            try (Response response = call.execute()) {
                ResponseBody body = response.body();
                assert body != null;
                Scanner scanner = new Scanner(body.byteStream());
                while (scanner.hasNextLine()) {
                    String[] parts = scanner.nextLine().split("\t");
                    String jobName = parts[0];
                    String buildNumber = parts[1];
                    String consoleTextUrl = parts[2];
                    failed.add(new Failed(jobName, buildNumber, consoleTextUrl));
                }
            }
            log.info("Queuing up fetches for {} failed build(s) to store in {}", failed.size(), outputDir);
            CountDownLatch latch = new CountDownLatch(failed.size());
            for (Failed f : failed) {
                Request r = new Request.Builder()
                        .get()
                        .url(f.getConsoleTextUrl())
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
                                Files.copy(body.byteStream(), outputDir.resolve(String.join(".", f.getJobName(), f.getBuildNumber(), "txt")), StandardCopyOption.REPLACE_EXISTING);
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
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Data
    private static class Failed {
        private final String jobName;
        private final String buildNumber;
        private final String consoleTextUrl;
    }
}
