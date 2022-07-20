package io.moderne.jenkins.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class JenkinsJobFetcher {

    private final WebClient webClient;

    public JenkinsJobFetcher() {
        final int size = 16 * 1024 * 1024;
        final ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(size))
                .build();
        final ConnectionProvider connectionProvider = ConnectionProvider.builder("myConnectionPool")
                .maxConnections(100)
                .pendingAcquireMaxCount(Integer.MAX_VALUE)
                .build();
        ReactorClientHttpConnector clientHttpConnector = new ReactorClientHttpConnector(HttpClient.create(connectionProvider));
        webClient = WebClient.builder()
                .exchangeStrategies(strategies)
                .clientConnector(clientHttpConnector)
                .baseUrl("https://jenkins.moderne.ninja")
                .defaultHeaders(headers -> headers.setBasicAuth("greg@moderne.io", "1150e72a691ea747e11824c7e9672563e3"))
                .build();
    }



    public Flux<JenkinsJobSummary> fetchJenkinsJobSummaries() {
        return webClient.get()
                .uri("/job/ingest/api/json?tree=jobs[name,color]")
                .retrieve().bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                }).flatMapMany(map -> {
                    List<Map<String, Object>> jobs = (List<Map<String, Object>>) map.get("jobs");
                    List<JenkinsJobSummary> jenkinsJobSummaryList = new ArrayList<>();
                    for (Map<String, Object> job : jobs) {
                        jenkinsJobSummaryList.add(new JenkinsJobSummary((String) job.get("name"), (String) job.get("color")));
                    }
                    return Flux.fromIterable(jenkinsJobSummaryList);
                })
                .retryWhen(Retry.backoff(10, Duration.ofSeconds(1)));
    }

    @SuppressWarnings("unchecked")
    public Mono<JenkinsJob> fetchJenkinsJob(String name) {
        return webClient.get()
                .uri("/job/ingest/job/{name}/api/json?tree=name,color,builds[number]", name)
                .retrieve().bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                }).map(map -> {
                    List<Map<String, Object>> builds = (List<Map<String, Object>>) map.get("builds");
                    List<Long> buildNumbers = new ArrayList<>();
                    for (Map<String, Object> build : builds) {
                        Object numberObj = build.get("number");
                        if (numberObj instanceof Integer) {
                            buildNumbers.add(((Integer) numberObj).longValue());
                        } else {
                            buildNumbers.add((Long) numberObj);
                        }
                    }
                    return new JenkinsJob((String) map.get("name"), (String) map.get("color"), buildNumbers);
                })
                .retryWhen(Retry.backoff(10, Duration.ofSeconds(1)));
    }

    public Mono<JenkinsJobBuild> fetchJenkinsJobBuild(String jobName, long buildNumber) {
        return webClient.get()
                .uri("/job/ingest/job/{jobName}/{buildNumber}/api/json?tree=number,timestamp,result", jobName, buildNumber)
                .retrieve().bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                }).map(map -> {
                    Object numberObj = map.get("number");
                    Long number = null;
                    if (numberObj instanceof Integer) {
                        number = ((Integer) numberObj).longValue();
                    } else {
                        number = (Long) numberObj;
                    }
                    Object timestampObj = map.get("timestamp");
                    Long timestamp = null;
                    if (timestampObj instanceof Integer) {
                        timestamp = ((Integer) timestampObj).longValue();
                    } else {
                        timestamp = (Long) timestampObj;
                    }
                    return new JenkinsJobBuild(jobName, number, timestamp, (String) map.get("result"));
                })
                .retryWhen(Retry.backoff(10, Duration.ofSeconds(1)));
    }

    public static void main(String[] args) {
        new JenkinsJobFetcher().printFailedJobs().block();
    }

    private Mono<Void> printFailedJobs() {
        Flux<JenkinsJobSummary> jenkinsJobs = fetchJenkinsJobSummaries().cache().doOnError(t -> log.error("Error fetching jobs", t));
        return jenkinsJobs.filter(jenkinsJobSummary -> !jenkinsJobSummary.color().equals("blue"))
                .collectList().doOnNext(failedJobs -> log.info("Failed job count: {}", failedJobs.size()))
                        .then(jenkinsJobs.collectList().doOnNext(jobs -> log.info("Job count: " + jobs.size())))
                .then();
    }
}
