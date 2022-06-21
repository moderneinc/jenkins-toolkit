package io.moderne.jenkins.failjobs;

import info.debatty.java.stringsimilarity.experimental.Sift4;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@RequiredArgsConstructor
public class CategorizeFailures {
    private static final int THRESHOLD = 20;
    private final Path dir;

    public static void main(String[] args) {
        new CategorizeFailures(
                Paths.get("jenkins-failed")
        ).run(args);
    }

    public void run(String[] args) {
        group(extract()).forEach((k, v) -> {
            log.info("{} -> {}", k.split("\n")[0], v);
        });
    }

    public List<String> extract() {
        List<String> exceptions = new LinkedList<>();
        try (Stream<Path> files = Files.list(dir).sorted()) {
            files.forEach(f -> {
                try (Stream<String> lines = Files.lines(f)) {
                    exceptions.add(lines
                            .filter(l -> l.contains("Exception:") || l.contains("Error:") || l.startsWith("\tat"))
                            .collect(Collectors.joining("\n")));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            return exceptions;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Map<String, Integer> group(List<String> exceptions) {
        Map<String, Integer> counts = new HashMap<>();
        Sift4 sift4 = new Sift4();
        sift4.setMaxOffset(100);
        for (String exception : exceptions) {
            Optional<String> found = counts.keySet().stream()
                    .filter(exn -> {
                        double dist = sift4.distance(exn, exception);
                        return dist * 100 / exception.length() < THRESHOLD;
                    })
                    .findFirst();
            counts.compute(found.orElse(exception), (key, old) -> Optional.ofNullable(old).orElse(0) + 1);
        }
        return counts;
    }
}
