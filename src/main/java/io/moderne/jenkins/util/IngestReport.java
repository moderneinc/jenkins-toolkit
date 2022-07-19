package io.moderne.jenkins.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class IngestReport {
    private final OkHttpClient client;
    private final HttpUrl url;
    private final Path script;

    public static void main(String[] args) {
        String base = System.getProperty("url", "https://jenkins.moderne.ninja");
        HttpUrl url = base.endsWith("/") ? HttpUrl.get(base + "scriptText") : HttpUrl.get(base + "/scriptText");
        Path groovyScript = Paths.get("src/jenkins/groovy/ingest-report.groovy");

        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.MINUTES)
                .callTimeout(1, TimeUnit.MINUTES)
                .readTimeout(5, TimeUnit.MINUTES)
                .build();
        new IngestReport(okHttpClient, url, groovyScript).run(args);
    }

    private String getCrumb(OkHttpClient client, String credential) {
        Request request = new Request.Builder().get().url("https://jenkins.moderne.ninja/crumbIssuer/api/json")
                .header("Authorization", credential).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.error("{}: Unexpected status {}", request.url(), response);
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
            try (Response response = call.execute()) {
                ResponseBody body = response.body();
                assert body != null;
                System.out.println(body.string());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
