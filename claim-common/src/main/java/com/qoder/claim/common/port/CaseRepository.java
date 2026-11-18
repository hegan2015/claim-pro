package com.qoder.claim.common.port;

import com.qoder.claim.common.model.CaseStatus;
import com.qoder.claim.common.model.ClaimCase;

import java.util.List;
import java.util.Optional;

public interface CaseRepository {

    void save(ClaimCase claimCase);

    void update(ClaimCase claimCase);

    Optional<ClaimCase> find(String caseNo);

    void updateStatus(String caseNo, CaseStatus status);

    List<ClaimCase> list(String status, String channel, int limit);
}