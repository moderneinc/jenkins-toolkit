package io.moderne.utils;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class RepoCsvBatch {

    Path inputFile;
    Path outputFile;

    private static final IngestState REPOS_HEADER = new IngestState("repoName", "branch", "javaVersion", "style", "buildTool");

    IngestStateTransformer transformer = originalState -> originalState;

    public static void main(String[] args) {

        //Note: If you only supply one filename, this will read, transform and then replace that one file.
        RepoCsvBatch batch = new RepoCsvBatch();

        String inputFileName = (args.length > 0) ? args[0] : "repos.csv";
        String outputFileName = (args.length > 1) ? args[1] : inputFileName;
        batch.inputFile = Paths.get(inputFileName).toAbsolutePath();

        batch.outputFile = Paths.get(outputFileName).toAbsolutePath();
        batch.run();
    }

    public void run() {

            Set<IngestState> ingestedRepos = new HashSet<>(6000);

            try (Stream<String> lines = Files.lines(inputFile)) {
                lines.forEach(l -> {
                IngestState state = parseCsv(l);
                if (state != null && !REPOS_HEADER.equals(state)) {
                    if (!ingestedRepos.contains(state)) {
                        ingestedRepos.add(state);
                    } else {
                        System.out.println("Duplicate Repo " + state.repoName + ":" + state.branch);
                    }
                }
            });

            List<IngestState> outRepos = new ArrayList<>(ingestedRepos.size());
            outRepos.add(REPOS_HEADER);
            for (IngestState state : ingestedRepos) {
                IngestState after = transformer.transform(state);
                if (after != null) {
                    outRepos.add(after);
                }
            }

            outRepos.sort( (o1, o2) -> o1.repoName.compareTo(o2.getRepoName()));

            Files.write(outputFile, outRepos.stream().map(IngestState::toCsv).collect(Collectors.toList()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static IngestState parseCsv(String line) {
        String[] values = line.split(",");
        String repoName;
        String branch;
        String javaVersion;
        String style;
        String buildTool;

        if (values.length > 0) {
            repoName = values[0].trim();
        } else {
            return null;
        }
        if (values.length > 1) {
            branch = values[1].trim();
        } else {
            branch = "master";
        }
        if (values.length > 2) {
            javaVersion = values[2].trim();
        } else {
            javaVersion = "8";
        }
        if (values.length > 3) {
            style = values[3].trim();
        } else {
            style = "";
        }
        if (values.length > 4) {
            buildTool = values[4].trim();
        } else {
            return null;
        }
        return new IngestState(repoName, branch, javaVersion, style, buildTool);
    }
}
