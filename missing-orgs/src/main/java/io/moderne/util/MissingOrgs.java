package io.moderne.util;

import com.netflix.graphql.dgs.client.GraphQLResponse;
import com.netflix.graphql.dgs.client.MonoGraphQLClient;
import com.netflix.graphql.dgs.client.WebClientGraphQLClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class MissingOrgs {

    public static void main(String[] args) {
        new MissingOrgs().run();
    }

    private String orgRepositoriesQuery = "" +
            "query orgRepositories($orgName: String!) {" +
            "  organizations(name: $orgName) {" +
            "    repositories {" +
            "      origin" +
            "      path" +
            "      branch" +
            "    }" +
            "  }" +
            "}";

    public record Repository(String origin, String path, String branch) {
    }

    record JenkinsJob(String name, String color) {
    }

    @SuppressWarnings("unchecked")
    public void run() {
        final int size = 16 * 1024 * 1024;
        final ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(size))
                .build();
        WebClient webClient = WebClient.builder().exchangeStrategies(strategies).baseUrl("https://api.public.moderne.io/graphql").build();
        WebClientGraphQLClient graphQLClient = MonoGraphQLClient.createWithWebClient(webClient, headers -> {
            headers.setBearerAuth("mat-zliFfgWWrh-1ADuP1oX5EWYYKjVAVK3X");
        });

        Mono<List<JenkinsJob>> jenkinsJobs = webClient.get()
                .uri("https://jenkins.moderne.ninja/job/ingest/api/json")
                .headers(headers -> {
                    headers.setBasicAuth("greg@moderne.io", "1150e72a691ea747e11824c7e9672563e3");
                }).retrieve().bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                }).flatMapMany(map -> {
                    List<Map<String, Object>> jobs = (List<Map<String, Object>>) map.get("jobs");
                    List<JenkinsJob> jenkinsJobList = new ArrayList<>();
                    for (Map<String, Object> job : jobs) {
                        String name = (String) job.get("name");
                        String color = (String) job.get("color");
                        if (name.startsWith("jenkinsci_") && color.equals("blue")) {
                            jenkinsJobList.add(new JenkinsJob(name, (String) job.get("color")));
                        }
                    }
                    return Flux.fromIterable(jenkinsJobList);
                }).collectList();

        Mono<List<Repository>> orgRepos = graphQLClient.reactiveExecuteQuery(orgRepositoriesQuery, Map.of("orgName", "jenkinsci")).map(resp -> {
            List<Repository> repos = new ArrayList<>();
            Map<String, Object> data = resp.getData();
            List<Map<String, Object>> organizations = (List<Map<String, Object>>) data.get("organizations");
            for (Map<String, Object> organization : organizations) {
                List<Map<String, Object>> repositories = (List<Map<String, Object>>) organization.get("repositories");
                for (Map<String, Object> repository : repositories) {
                    repos.add(new Repository(
                            (String) repository.get("origin"),
                            (String) repository.get("path"),
                            (String) repository.get("branch")
                    ));
                }
            }
            return repos;
        });

        Mono.zip(jenkinsJobs, orgRepos).doOnNext(t -> {
            List<JenkinsJob> jenkinsJobList = t.getT1();
            List<Repository> orgRepoList = t.getT2();
            Map<String, Repository> repoMap = orgRepoList.stream().collect(Collectors.toMap(Repository::path, Function.identity()));
            for (JenkinsJob jenkinsJob : jenkinsJobList) {
                Repository repository = repoMap.get(jenkinsJob.name().replaceFirst("_", "/"));
                if (repository == null) {
                    log.info("Missing repository: {}", jenkinsJob.name());
                }
            }
        }).block();

    }
}
