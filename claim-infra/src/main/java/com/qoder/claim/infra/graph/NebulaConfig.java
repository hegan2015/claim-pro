package com.qoder.claim.infra.graph;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(NebulaProperties.class)
public class NebulaConfig {

    @Bean
    @ConditionalOnProperty(prefix = "claim.graph.nebula", name = "enabled", havingValue = "true")
    public NebulaGraphStore nebulaGraphStore(NebulaProperties props) {
        return new NebulaGraphStore(props);
    }
}