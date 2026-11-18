package com.qoder.claim.common.port;

import com.qoder.claim.common.model.Material;

import java.util.List;

public interface MaterialRepository {

    void save(Material material);

    List<Material> list(String caseNo);

    long count(String caseNo);
}