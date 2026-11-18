package com.qoder.claim.infra.jdbc;

import com.qoder.claim.common.model.Material;
import com.qoder.claim.common.port.MaterialRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JdbcMaterialRepository implements MaterialRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Material> MAPPER = (rs, i) -> {
        Material m = new Material();
        m.setMaterialId(rs.getString("material_id"));
        m.setCaseNo(rs.getString("case_no"));
        m.setType(rs.getString("type"));
        m.setStorageKey(rs.getString("storage_key"));
        m.setSha256(rs.getString("sha256"));
        m.setConfidence(rs.getDouble("confidence"));
        return m;
    };

    public JdbcMaterialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Material m) {
        jdbc.update("INSERT INTO claim_material(material_id, case_no, type, storage_key, sha256, confidence) VALUES(?,?,?,?,?,?)",
                m.getMaterialId(), m.getCaseNo(), m.getType(), m.getStorageKey(), m.getSha256(), m.getConfidence());
    }

    @Override
    public List<Material> list(String caseNo) {
        return jdbc.query("SELECT * FROM claim_material WHERE case_no=? ORDER BY id", MAPPER, caseNo);
    }

    @Override
    public long count(String caseNo) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM claim_material WHERE case_no=?", Long.class, caseNo);
        return n == null ? 0 : n;
    }
}