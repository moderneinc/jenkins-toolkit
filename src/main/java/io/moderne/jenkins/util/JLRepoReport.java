package io.moderne.jenkins.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class JLRepoReport {

    public static void main(String[] args) {
        new JLRepoReport(new ModerneSaasRepositoryFetcher(), new JenkinsJobFetcher()).run();
    }

    private final ModerneSaasRepositoryFetcher moderneSaasRepositoryFetcher;
    private final JenkinsJobFetcher jenkinsJobFetcher;

    @SuppressWarnings("unchecked")
    public void run() {

        Mono<List<JenkinsJobSummary>> jenkinsJobs = jenkinsJobFetcher.fetchJenkinsJobSummaries().collectList();

        Mono<List<ModerneSaasRepository>> orgRepos = moderneSaasRepositoryFetcher.fetchRepositories(null);

        Mono.zip(jenkinsJobs, orgRepos).doOnNext(t -> {
            List<JenkinsJobSummary> jenkinsJobSummaryList = t.getT1();
            List<ModerneSaasRepository> orgRepoList = t.getT2();
            Map<String, JenkinsJobSummary> jenkinsJobMap = jenkinsJobSummaryList.stream().collect(
                    Collectors.toMap(
                            jenkinsJobSummary -> jenkinsJobSummary.name().replaceFirst("_", "/"),
                            Function.identity()
                    ));
            analyze("tmp-info-disc.txt", orgRepoList, jenkinsJobMap);
            analyze("zip_slip.txt", orgRepoList, jenkinsJobMap);
        }).block();

    }

    private void analyze(String filename, List<ModerneSaasRepository> orgRepoList, Map<String, JenkinsJobSummary> jenkinsJobMap) {
        try (BufferedWriter writer = Files.newBufferedWriter(Path.of(filename + ".csv"))) {
            writer.write("repo, public_repo_exists, jenkins_job_status\n");
            InputStream resourceAsStream = getClass().getResourceAsStream("/jl/" + filename);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(resourceAsStream)))) {
                String repo;
                while ((repo = reader.readLine()) != null) {
                    writer.write(repo + ", ");
                    String orgRepoName = repo.substring("https://github.com".length() + 1);
                    ModerneSaasRepository moderneRepo = orgRepoList.stream().filter(orgRepo -> orgRepo.path().equals(orgRepoName)).findFirst().orElse(null);
                    writer.write(Boolean.toString(moderneRepo != null) + ", ");
                    JenkinsJobSummary jenkinsJobSummary = jenkinsJobMap.get(orgRepoName);
                    writer.write(jenkinsJobSummary == null ? "not found" : jenkinsJobSummary.color());
                    writer.write("\n");
                }
            }
        }  catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
