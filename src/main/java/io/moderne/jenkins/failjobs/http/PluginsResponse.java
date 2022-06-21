package io.moderne.jenkins.failjobs.http;

import lombok.Data;

import java.util.List;

@Data
public class PluginsResponse {
    private List<Plugin> plugins;
}
