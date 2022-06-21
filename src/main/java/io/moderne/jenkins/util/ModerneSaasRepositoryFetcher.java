package io.moderne.jenkins.util;

import com.netflix.graphql.dgs.client.MonoGraphQLClient;
import com.netflix.graphql.dgs.client.WebClientGraphQLClient;
import org.springframework.lang.Nullable;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ModerneSaasRepositoryFetcher {

    private static final String orgRepositoriesQuery = "" +
            "query orgRepositories($orgName: String!) {" +
            "  organizations(name: $orgName) {" +
            "    repositories {" +
            "      origin" +
            "      path" +
            "      branch" +
            "    }" +
            "  }" +
            "}";

    private static final String allOrgRepositoriesQuery = "" +
            "query orgRepositories {" +
            "  organizations {" +
            "    repositories {" +
            "      origin" +
            "      path" +
            "      branch" +
            "    }" +
            "  }" +
            "}";

    private final WebClientGraphQLClient graphQLClient;

    public ModerneSaasRepositoryFetcher() {
        final int size = 16 * 1024 * 1024;
        final ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(size))
                .build();
        WebClient webClient = WebClient.builder().exchangeStrategies(strategies).baseUrl("https://api.public.moderne.io/graphql").build();
        graphQLClient = MonoGraphQLClient.createWithWebClient(webClient, headers -> {
            headers.setBearerAuth("mat-zliFfgWWrh-1ADuP1oX5EWYYKjVAVK3X");
        });
    }

    public Mono<List<ModerneSaasRepository>> fetchRepositories(@Nullable String orgName) {
        return graphQLClient.reactiveExecuteQuery(orgName == null ? allOrgRepositoriesQuery : orgRepositoriesQuery, orgName == null ? new HashMap<>() : Map.of("orgName", orgName)).map(resp -> {
            List<ModerneSaasRepository> repos = new ArrayList<>();
            Map<String, Object> data = resp.getData();
            List<Map<String, Object>> organizations = (List<Map<String, Object>>) data.get("organizations");
            for (Map<String, Object> organization : organizations) {
                List<Map<String, Object>> repositories = (List<Map<String, Object>>) organization.get("repositories");
                for (Map<String, Object> repository : repositories) {
                    repos.add(new ModerneSaasRepository(
                            (String) repository.get("origin"),
                            (String) repository.get("path"),
                            (String) repository.get("branch")
                    ));
                }
            }
            return repos;
        });
    }

}
