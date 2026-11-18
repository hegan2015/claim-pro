package com.qoder.claim.infra.minio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.claim.common.port.EvidenceStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 证据快照哈希链：digest = SHA-256(prevHash | outputJson)，逐条挂链，防篡改。
 * 索引落库，对象字节由子类存储（MinIO WORM / 本地文件系统）。
 */
public abstract class AbstractEvidenceStore implements EvidenceStore {

    protected final JdbcTemplate jdbc;
    protected final ObjectMapper json = new ObjectMapper();

    protected AbstractEvidenceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    protected abstract void putBytes(String objectKey, byte[] bytes) throws Exception;

    protected abstract byte[] getBytes(String objectKey) throws Exception;

    @Override
    public synchronized String snapshot(String caseNo, String step, String inputHash, String outputJson,
                                        String modelVersion, String promptVersion) {
        try {
            List<Map<String, Object>> last = jdbc.queryForList(
                    "SELECT seq, sha256 FROM evidence_index WHERE case_no=? ORDER BY seq DESC LIMIT 1", caseNo);
            int seq = last.isEmpty() ? 1 : ((Number) last.get(0).get("seq")).intValue() + 1;
            String prevHash = last.isEmpty() ? "GENESIS" : (String) last.get(0).get("sha256");
            String digest = sha256(prevHash + "|" + outputJson);
            String evidenceId = caseNo + "-EV-" + seq;
            String objectKey = caseNo + "/" + seq + ".json";
            long ts = System.currentTimeMillis();

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("evidenceId", evidenceId);
            payload.put("caseNo", caseNo);
            payload.put("step", step);
            payload.put("seq", seq);
            payload.put("inputHash", inputHash);
            payload.put("outputJson", outputJson);
            payload.put("modelVersion", modelVersion);
            payload.put("promptVersion", promptVersion);
            payload.put("prevHash", prevHash);
            payload.put("sha256", digest);
            payload.put("ts", ts);
            putBytes(objectKey, json.writeValueAsBytes(payload));

            jdbc.update("INSERT INTO evidence_index(case_no, seq, evidence_id, object_key, sha256, prev_hash, step, ts) " +
                            "VALUES(?,?,?,?,?,?,?,?)",
                    caseNo, seq, evidenceId, objectKey, digest, prevHash, step, ts);
            return evidenceId;
        } catch (Exception e) {
            throw new IllegalStateException("证据快照写入失败: " + caseNo, e);
        }
    }

    @Override
    public boolean verifyChain(String caseNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM evidence_index WHERE case_no=? ORDER BY seq", caseNo);
        String prev = "GENESIS";
        for (Map<String, Object> row : rows) {
            try {
                byte[] bytes = getBytes((String) row.get("object_key"));
                Map<String, Object> payload = json.readValue(bytes, Map.class);
                String outputJson = (String) payload.get("outputJson");
                String expect = sha256(prev + "|" + outputJson);
                if (!expect.equals(row.get("sha256")) || !prev.equals(row.get("prev_hash"))) {
                    return false;
                }
                prev = (String) row.get("sha256");
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    protected static String sha256(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}