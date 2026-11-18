package com.qoder.claim.gateway.batch;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * XXL-Job 执行器（claim.xxljob.enabled=true 时装配，mysql/default 档位）。
 * 调度中心由 ops-pro claim-shared 栈纳管（claim-shared-xxl-job-admin，:8094）。
 * executor-ip 默认 host.docker.internal：调度中心在容器内，须经 Docker Desktop DNS 回调宿主执行器。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "claim.xxljob", name = "enabled", havingValue = "true")
public class XxlJobConfig {

    @Value("${claim.xxljob.admin-addresses}")
    private String adminAddresses;
    @Value("${claim.xxljob.appname}")
    private String appname;
    @Value("${claim.xxljob.executor-ip:}")
    private String ip;
    @Value("${claim.xxljob.port:9999}")
    private int port;
    @Value("${claim.xxljob.access-token:}")
    private String accessToken;
    @Value("${claim.xxljob.logpath:logs/xxl-job}")
    private String logPath;
    @Value("${claim.xxljob.logretentiondays:30}")
    private int logRetentionDays;

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor() {
        log.info("XXL-Job 执行器装配: admin={} appname={} registryIp={} port={}", adminAddresses, appname, ip, port);
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);
        executor.setAppname(appname);
        executor.setIp(ip);
        executor.setPort(port);
        executor.setAccessToken(accessToken);
        executor.setLogPath(logPath);
        executor.setLogRetentionDays(logRetentionDays);
        return executor;
    }
}