package com.qoder.claim.core;

import com.qoder.claim.core.rule.CoverageDecision;
import com.qoder.claim.core.rule.CoverageRuleEngine;
import com.qoder.claim.core.rule.PolicySnapshot;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CoverageRuleEngineTest {

    private final CoverageRuleEngine engine = new CoverageRuleEngine();

    private PolicySnapshot policy() {
        PolicySnapshot p = new PolicySnapshot();
        p.setPolicyNo("P1");
        p.setEffectiveDate(LocalDate.of(2026, 1, 1));
        p.setExpiryDate(LocalDate.of(2026, 12, 31));
        p.setCoverageTypes(List.of("COLLISION", "THEFT"));
        p.setClauseVersion("clause-2026-v1");
        return p;
    }

    @Test
    void coveredCollision() {
        CoverageDecision d = engine.evaluate(policy(), LocalDate.of(2026, 11, 17),
                "COLLISION", false, false, false);
        assertTrue(d.isCovered());
        assertTrue(d.getHitClauses().contains("CLAUSE-TYPE-OK"));
    }

    @Test
    void outsidePeriod() {
        CoverageDecision d = engine.evaluate(policy(), LocalDate.of(2025, 12, 31),
                "COLLISION", false, false, false);
        assertFalse(d.isCovered());
        assertTrue(d.getExclusions().contains("EXCL-PERIOD"));
    }

    @Test
    void typeNotCovered() {
        CoverageDecision d = engine.evaluate(policy(), LocalDate.of(2026, 11, 17),
                "FLOOD", false, false, false);
        assertFalse(d.isCovered());
        assertTrue(d.getExclusions().contains("EXCL-TYPE"));
    }

    @Test
    void statutoryExclusions() {
        CoverageDecision d = engine.evaluate(policy(), LocalDate.of(2026, 11, 17),
                "COLLISION", true, false, false);
        assertFalse(d.isCovered());
        assertTrue(d.getExclusions().contains("EXCL-DRUNK"));
    }
}