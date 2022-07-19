package io.moderne.jenkins.util;

import com.netflix.graphql.dgs.client.MonoGraphQLClient;
import com.netflix.graphql.dgs.client.WebClientGraphQLClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class MissingOrgs {

    public static void main(String[] args) {
        new MissingOrgs(new ModerneSaasRepositoryFetcher(), new JenkinsJobFetcher()).run();
    }

    private final ModerneSaasRepositoryFetcher moderneSaasRepositoryFetcher;
    private final JenkinsJobFetcher jenkinsJobFetcher;

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

        Mono<List<JenkinsJobSummary>> blueJenkinsJobs = jenkinsJobFetcher.fetchJenkinsJobSummaries().filter(jenkinsJobSummary ->
            jenkinsJobSummary.name().startsWith("jenkinsci_") && jenkinsJobSummary.color().equals("blue")
        ).collectList();

        Mono<List<ModerneSaasRepository>> moderneSaasRepositories = moderneSaasRepositoryFetcher.fetchRepositories("jenkinsci");

        Mono.zip(blueJenkinsJobs, moderneSaasRepositories).doOnNext(t -> {
            List<JenkinsJobSummary> jenkinsJobSummaryList = t.getT1();
            List<ModerneSaasRepository> orgRepoList = t.getT2();
            Map<String, ModerneSaasRepository> repoMap = orgRepoList.stream().collect(Collectors.toMap(ModerneSaasRepository::path, Function.identity()));
            for (JenkinsJobSummary jenkinsJobSummary : jenkinsJobSummaryList) {
                ModerneSaasRepository repository = repoMap.get(jenkinsJobSummary.name().replaceFirst("_", "/"));
                if (repository == null) {
                    log.info("Missing repository: {}", jenkinsJobSummary.name());
                }
            }
        }).block();

    }
}
