package io.moderne.jenkins.failjobs;

import info.debatty.java.stringsimilarity.experimental.Sift4;
import lombok.AllArgsConstructor;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@AllArgsConstructor
public class CategorizeFailuresHtml {

    private static final int THRESHOLD = 20;
    private final Path jenkinsFailedJobLogsDir;
    private Path htmlOutDir;

    private static final String HTML_HEADER = "<!doctype html>\n" +
            "<html lang=\"en\">\n" +
            "  <head>\n" +
            "    <meta charset=\"utf-8\">\n" +
            "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1, shrink-to-fit=no\">\n" +
            "\n" +
            "    <!-- Bootstrap CSS -->\n" +
            "    <link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/npm/bootstrap@4.0.0/dist/css/bootstrap.min.css\" integrity=\"sha384-Gn5384xqQ1aoWXA+058RXPxPg6fy4IWvTNh0E263XmFcJlSAwiGgFAW/dAiS6JXm\" crossorigin=\"anonymous\">\n" +
            "\n" +
            "  </head>\n";

    public static void main(String[] args) {
        new CategorizeFailuresHtml(
                Paths.get("jenkins-failed"),
                Paths.get("jenkins-failed-html")
        ).run();
    }

    public void run() {
        try {
            if (Files.exists(htmlOutDir)) {
                FileSystemUtils.deleteRecursively(htmlOutDir);
            }
            Files.createDirectory(htmlOutDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try (PrintWriter indexWriter = new PrintWriter(Files.newOutputStream(htmlOutDir.resolve("index.html"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            indexWriter.write(HTML_HEADER);
            indexWriter.write("<body>\n");
            indexWriter.write("<table class=\"table\"><tr><th>Message</th><th>Count</th></tr>\n");
            AtomicInteger i = new AtomicInteger(0);
            group(extract()).forEach((k, v) -> {
                int groupIndex = i.getAndIncrement();
                try (PrintWriter groupWriter = new PrintWriter(Files.newOutputStream(htmlOutDir.resolve(groupIndex + ".html"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
                    groupWriter.write(HTML_HEADER);
                    groupWriter.write("<body><p>" + k + "</p><ul>");
                    v.forEach(consoleLogPath -> groupWriter.write("<li><a href=\"../jenkins-failed/" + consoleLogPath.getFileName() + "\">" + consoleLogPath.getFileName() + "</a></li>"));
                    groupWriter.write("</ul></body></html>");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                String exceptionMessage = k.split("\n")[0];
                if (exceptionMessage.isBlank()) {
                    exceptionMessage = "NO EXCEPTION";
                }
                indexWriter.write("<tr><td><a href=\"" + groupIndex + ".html\">" + exceptionMessage + "</a></td><td>" + v.size() + "</td></tr>\n");

            });
            indexWriter.write("</table>\n");
            indexWriter.write("</body></html>");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private record JobError(String exception, Path consoleLog) {
    }

    public List<JobError> extract() {
        List<JobError> exceptions = new ArrayList<>();
        try (Stream<Path> files = Files.list(jenkinsFailedJobLogsDir).sorted()) {
            files.forEach(f -> {
                try (Stream<String> lines = Files.lines(f)) {
                    exceptions.add(new JobError(lines
                            .filter(l -> l.contains("Exception:") ||
                                    l.contains("Error:") ||
                                    l.contains("ERROR") ||
                                    l.contains("An exception occurred") ||
                                    l.startsWith("Extension with name") ||
                                    l.startsWith("Could not find method mavenLocal()") ||
                                    l.contains("Could not find com.fasterxml.jackson.datatype:jackson-datatype-jsr310:RELEASE") ||
                                    l.contains("Could not resolve all files for configuration") ||
                                    l.contains("Publication only contains dependencies and/or constraints without a version") ||
                                    l.contains("Could not find method pluginManagement()") ||
                                    l.contains("No signature of method: org.gradle.api.internal.tasks.RealizableTaskCollection.configureEach() is applicable for argument types") ||
                                    l.startsWith("\tat"))
                            .collect(Collectors.joining("\n")), f));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            return exceptions;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Map<String, List<Path>> group(List<JobError> jobErrors) {
        Map<String, List<Path>> groups = new HashMap<>();
        Sift4 sift4 = new Sift4();
        sift4.setMaxOffset(100);
        for (JobError jobError : jobErrors) {
            Optional<String> found = groups.keySet().stream()
                    .filter(exn -> {
                        double dist = sift4.distance(exn, jobError.exception());
                        return dist * 100 / jobError.exception().length() < THRESHOLD;
                    })
                    .findFirst();
            List<Path> paths = groups.computeIfAbsent(found.orElse(jobError.exception()), k -> new ArrayList<>());
            paths.add(jobError.consoleLog());
        }
        List<Map.Entry<String, List<Path>>> list = new ArrayList<>(groups.entrySet());
        list.sort((o1, o2) -> Integer.compare(o2.getValue().size(), o1.getValue().size()));
        Map<String, List<Path>> map = new LinkedHashMap<>(list.size());
        for (Map.Entry<String, List<Path>> it : list) {
            map.put(it.getKey(), it.getValue());
        }
        return map;
    }
}
