package com.qoder.claim.core;

import com.qoder.claim.common.model.CalcResult;
import com.qoder.claim.core.calc.ClaimCalculator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClaimCalculatorTest {

    private final ClaimCalculator calc = new ClaimCalculator();

    @Test
    void fullLiabilityNoDeductible() {
        CalcResult r = calc.calculate("C1", 8200_00, 0, 0, 1_000_000_00, 100);
        assertEquals(8200_00, r.getPayableCents());
    }

    @Test
    void deductibleAndDepreciationAndLiability() {
        // 定损10000元，折旧10% → 9000；责任70% → 6300；免赔500 → 5800
        CalcResult r = calc.calculate("C2", 10_000_00, 500_00, 10, 1_000_000_00, 70);
        assertEquals(1000_00, r.getDepreciationCents());
        assertEquals(5800_00, r.getPayableCents());
    }

    @Test
    void limitCaps() {
        CalcResult r = calc.calculate("C3", 50_000_00, 0, 0, 20_000_00, 100);
        assertEquals(20_000_00, r.getPayableCents());
    }

    @Test
    void floorAtZero() {
        CalcResult r = calc.calculate("C4", 100_00, 500_00, 0, 1_000_000_00, 100);
        assertEquals(0, r.getPayableCents());
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class,
                () -> calc.calculate("C5", -1, 0, 0, 100, 100));
        assertThrows(IllegalArgumentException.class,
                () -> calc.calculate("C6", 100, 0, 101, 100, 100));
    }
}