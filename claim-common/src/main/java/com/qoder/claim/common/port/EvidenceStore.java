package com.qoder.claim.common.port;

/** 证据快照端口。生产实现=MinIO WORM + SHA-256 哈希链，保留≥10年。 */
public interface EvidenceStore {

    /** 写入快照并挂接哈希链，返回证据 ID。 */
    String snapshot(String caseNo, String step, String inputHash, String outputJson,
                    String modelVersion, String promptVersion);

    /** 校验某案件证据链完整性。 */
    boolean verifyChain(String caseNo);
}