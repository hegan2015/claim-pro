package com.qoder.claim.infra.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 条款 RAG：复用共享栈 Qdrant + 本地 ONNX embedding（与 cs-pro 同模式）。不可达自动降级。 */
@Data
@ConfigurationProperties(prefix = "claim.clause-rag")
public class ClauseRagProperties {
    private boolean enabled = true;
    private String embeddingUrl = "http://127.0.0.1:8100";
    private String qdrantUrl = "http://127.0.0.1:6333";
    private String collection = "claim_clauses";
    private int topK = 3;
}