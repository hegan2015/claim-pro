package com.qoder.claim.infra.minio;

import com.qoder.claim.common.port.EvidenceStore;
import com.qoder.claim.common.port.MaterialFileStore;
import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class EvidenceConfig {

    @Bean
    @ConditionalOnProperty(prefix = "claim.minio", name = "enabled", havingValue = "true")
    public MinioClient minioClient(MinioProperties props) {
        return MinioClient.builder()
                .endpoint(props.getEndpoint())
                .credentials(props.getAccessKey(), props.getSecretKey())
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "claim.minio", name = "enabled", havingValue = "true")
    public EvidenceStore minioEvidenceStore(JdbcTemplate jdbc, MinioClient client, MinioProperties props) {
        return new MinioEvidenceStore(jdbc, client, props.getBucket());
    }

    @Bean
    @ConditionalOnProperty(prefix = "claim.minio", name = "enabled", havingValue = "false", matchIfMissing = true)
    public EvidenceStore localFsEvidenceStore(JdbcTemplate jdbc, MinioProperties props) {
        return new LocalFsEvidenceStore(jdbc, props.getLocalDir());
    }

    @Bean
    @ConditionalOnProperty(prefix = "claim.minio", name = "enabled", havingValue = "true")
    @org.springframework.context.annotation.DependsOn("minioEvidenceStore") // 桶由证据库初始化时创建
    public MaterialFileStore minioMaterialFileStore(MinioClient client, MinioProperties props) {
        return new MinioMaterialFileStore(client, props.getBucket());
    }

    @Bean
    @ConditionalOnProperty(prefix = "claim.minio", name = "enabled", havingValue = "false", matchIfMissing = true)
    public MaterialFileStore localMaterialFileStore(MinioProperties props) {
        return new LocalMaterialFileStore(props.getLocalDir());
    }
}