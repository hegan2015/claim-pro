package com.qoder.claim.infra.rag;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ClauseRagProperties.class)
public class ClauseRagConfig {

    @Bean
    public ClauseVectorIndex clauseVectorIndex(ClauseRagProperties props) {
        return new ClauseVectorIndex(props);
    }
}