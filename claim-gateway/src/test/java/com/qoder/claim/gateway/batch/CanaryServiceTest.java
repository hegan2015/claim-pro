package com.qoder.claim.gateway.batch;

import com.qoder.claim.workflow.CanaryProperties;
import com.qoder.claim.workflow.CanaryService;
import com.qoder.claim.workflow.RouteProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CanaryServiceTest {

    private CanaryService enabled(int percent) {
        CanaryProperties p = new CanaryProperties();
        p.setEnabled(true);
        p.setPercent(percent);
        return new CanaryService(p);
    }

    @Test
    void disabledAlwaysBaseline() {
        CanaryProperties p = new CanaryProperties();
        CanaryService svc = new CanaryService(p);
        assertEquals("BASELINE", svc.decideGroup("CLM-X", "HEAD", "AUTO"));
    }

    @Test
    void bucketingIsDeterministic() {
        CanaryService svc = enabled(50);
        String g1 = svc.decideGroup("CLM-20261120-ABC123", "HEAD", "AUTO");
        String g2 = svc.decideGroup("CLM-20261120-ABC123", "HEAD", "AUTO");
        assertEquals(g1, g2, "同案多次判定须一致");
    }

    @Test
    void hundredPercentAllCanary() {
        CanaryService svc = enabled(100);
        for (int i = 0; i < 20; i++) {
            assertEquals("CANARY", svc.decideGroup("CLM-" + i, "HEAD", "AUTO"));
        }
    }

    @Test
    void whitelistFilters() {
        CanaryProperties p = new CanaryProperties();
        p.setEnabled(true);
        p.setPercent(100);
        p.setOrgs("SH");
        CanaryService svc = new CanaryService(p);
        assertEquals("CANARY", svc.decideGroup("CLM-1", "SH", "AUTO"));
        assertEquals("BASELINE", svc.decideGroup("CLM-1", "HEAD", "AUTO"));
    }

    @Test
    void candidateRouteMergesBaseline() {
        CanaryProperties p = new CanaryProperties();
        p.setCandidateRiskLow(10);
        CanaryService svc = new CanaryService(p);
        RouteProperties base = new RouteProperties();
        RouteProperties cand = svc.candidateRoute(base);
        assertEquals(10, cand.getRiskLow());
        assertEquals(base.getFastLimitCents(), cand.getFastLimitCents());
        assertEquals(base.getRiskHigh(), cand.getRiskHigh());
    }

    @Test
    void runtimeUpdate() {
        CanaryService svc = enabled(0);
        svc.update(Map.of("percent", 100, "enabled", true));
        assertEquals("CANARY", svc.decideGroup("CLM-1", "HEAD", "AUTO"));
    }
}