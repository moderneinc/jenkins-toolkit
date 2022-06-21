package io.moderne.utils;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@AllArgsConstructor
public class IngestState {

    @EqualsAndHashCode.Include
    String repoName;

    @EqualsAndHashCode.Include
    String branch;
    String javaVersion;
    String style;
    String buildTool;

    public String toCsv() {
        return repoName + "," + branch + "," + javaVersion + "," + style + "," + buildTool;
    }
}

