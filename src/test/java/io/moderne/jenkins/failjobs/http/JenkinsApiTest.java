package io.moderne.jenkins.failjobs.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

import java.io.IOException;
import java.io.InputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class JenkinsApiTest {
    private final MockWebServer server = new MockWebServer();
    private JenkinsApi jenkinsApi;

    @BeforeEach
    void setup() throws IOException {
        server.start();
        jenkinsApi = new Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(JacksonConverterFactory.create(new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)))
                .build()
                .create(JenkinsApi.class);
    }

    @AfterEach
    void teardown() throws IOException {
        server.shutdown();
    }

    @Test
    void shouldDeserializeRequiredCore() throws IOException {
        server.enqueue(withBodyFromClasspath("/plugin-manager/required-core.json"));
        Response<PluginsResponse> response = jenkinsApi.fetchPluginsWithRequiredCore().execute();
        PluginsResponse responseBody = response.body();
        assertThat(responseBody).isNotNull();
        assertThat(responseBody.getPlugins()).containsExactly(
                new Plugin("backup", "1.375", "1.6.1", false),
                new Plugin("text-finder", "1.480", "1.10", true)
        );
    }

    static MockResponse withBodyFromClasspath(String name) {
        try (InputStream is = JenkinsApiTest.class.getResourceAsStream(name)) {
            assertThat(is).isNotNull();
            return new MockResponse().setBody(IOUtils.toString(is, UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
