package io.moderne.jenkins.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
        webClient = WebClient.builder().exchangeStrategies(strategies).baseUrl("https://api.public.moderne.io/graphql").build();
    }

    public Flux<JenkinsJob> fetchJenkinsJobs() {
        return webClient.get()
                .uri("https://jenkins.moderne.ninja/job/ingest/api/json")
                .headers(headers -> {
                    headers.setBasicAuth("greg@moderne.io", "1150e72a691ea747e11824c7e9672563e3");
                }).retrieve().bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                }).flatMapMany(map -> {
                    List<Map<String, Object>> jobs = (List<Map<String, Object>>) map.get("jobs");
                    List<JenkinsJob> jenkinsJobList = new ArrayList<>();
                    for (Map<String, Object> job : jobs) {
                        jenkinsJobList.add(new JenkinsJob((String) job.get("name"), (String) job.get("color")));
                    }
                    return Flux.fromIterable(jenkinsJobList);
                });
    }

    public static void main(String[] args) {
        new JenkinsJobFetcher().printFailedJobs().block();
    }

    private Mono<Void> printFailedJobs() {
        Flux<JenkinsJob> jenkinsJobs = fetchJenkinsJobs().cache().doOnError(t -> log.error("Error fetching jobs", t));
        return jenkinsJobs.filter(jenkinsJob -> !jenkinsJob.color().equals("blue"))
                .collectList().doOnNext(failedJobs -> log.info("Failed job count: {}", failedJobs.size()))
                        .then(jenkinsJobs.collectList().doOnNext(jobs -> log.info("Job count: " + jobs.size())))
                .then();
    }
}
