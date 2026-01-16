package com.example.bilibilimusic.agent.graph.definition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Component
public class GraphDefinitionLoader {

    private final ResourceLoader resourceLoader;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public GraphDefinitionLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public GraphDefinition load(String resourceLocation) {
        try {
            Resource resource = resourceLoader.getResource(resourceLocation);
            try (InputStream in = resource.getInputStream()) {
                return yamlMapper.readValue(in, GraphDefinition.class);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load graph definition: " + resourceLocation, e);
        }
    }
}

