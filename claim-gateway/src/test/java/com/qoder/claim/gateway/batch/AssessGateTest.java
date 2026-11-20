package com.qoder.claim.gateway.batch;

import com.qoder.claim.workflow.AssessGate;
import com.qoder.claim.workflow.AssessGateProperties;
import com.qoder.claim.workflow.RouteProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AssessGateTest {

    private final AssessGateProperties p = new AssessGateProperties();
    private final RouteProperties route = new RouteProperties();

    @Test
    void emptyEstimateForcesManual() {
        assertNotNull(AssessGate.reason(0, -1, 0.9, p, route));
        assertNotNull(AssessGate.reason(-100, 500000, 0.9, p, route));
    }

    @Test
    void divergenceBeyondRatioForcesManual() {
        assertNotNull(AssessGate.reason(420000, 6_800_000L, 0.9, p, route)); // 索赔6.8万 vs 估损4200，>3倍
        assertNull(AssessGate.reason(420000, 1_260_000L, 0.9, p, route));    // 恰为3倍，严格大于才拦
        assertNull(AssessGate.reason(420000, -1, 0.9, p, route));            // 描述未写索赔金额
        assertNull(AssessGate.reason(420000, 400000, 0.9, p, route));        // 索赔低于估损不拦
    }

    @Test
    void disabledGatePassesEverything() {
        AssessGateProperties off = new AssessGateProperties();
        off.setEnabled(false);
        assertNull(AssessGate.reason(0, 6_800_000L, 0.9, off, route));
    }

    @Test
    void lowConfidenceForcesManual() {
        assertNotNull(AssessGate.reason(420000, -1, 0.6, p, route));
        assertNull(AssessGate.reason(420000, -1, 0.85, p, route)); // 阈值边界放行
    }

    @Test
    void ratioIsConfigurable() {
        AssessGateProperties strict = new AssessGateProperties();
        strict.setDivergenceRatio(1.5);
        assertNotNull(AssessGate.reason(420000, 700000, 0.9, strict, route));
        assertNull(AssessGate.reason(420000, 600000, 0.9, strict, route));
    }
}