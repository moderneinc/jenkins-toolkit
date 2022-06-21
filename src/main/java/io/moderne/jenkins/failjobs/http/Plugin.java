package io.moderne.jenkins.failjobs.http;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Plugin {
    private String shortName;
    private String requiredCoreVersion;
    private String version;
    private boolean hasUpdate;
}
