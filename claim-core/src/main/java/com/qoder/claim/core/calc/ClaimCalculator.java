package com.qoder.claim.core.calc;

import com.qoder.claim.common.model.CalcResult;
import org.springframework.stereotype.Component;

/**
 * 理算计算器（纯代码，LLM 禁入）。
 * 赔付 = min(限额, (定损额 - 折旧) × 责任比例 - 免赔额)，下限 0。
 * agent 的理算草案仅作比对，最终金额以本计算器为准，差异落监控。
 */
@Component
public class ClaimCalculator {

    public CalcResult calculate(String caseNo, long estimateCents, long deductibleCents,
                                int depreciationPercent, long limitCents, int liabilityPercent) {
        if (estimateCents < 0 || deductibleCents < 0 || limitCents < 0) {
            throw new IllegalArgumentException("金额不得为负");
        }
        if (depreciationPercent < 0 || depreciationPercent > 100
                || liabilityPercent < 0 || liabilityPercent > 100) {
            throw new IllegalArgumentException("比例须在 0-100");
        }
        long depreciation = estimateCents * depreciationPercent / 100;
        long afterLiability = (estimateCents - depreciation) * liabilityPercent / 100;
        long afterDeductible = afterLiability - deductibleCents;
        long payable = Math.max(0, Math.min(limitCents, afterDeductible));

        CalcResult r = new CalcResult();
        r.setCaseNo(caseNo);
        r.setEstimateCents(estimateCents);
        r.setDeductibleCents(deductibleCents);
        r.setDepreciationCents(depreciation);
        r.setLimitCents(limitCents);
        r.setLiabilityPercent(liabilityPercent);
        r.setPayableCents(payable);
        r.setBreakdown(String.format("定损%d - 折旧%d = %d；×责任%d%% = %d；- 免赔%d = %d；限额%d → 赔付%d（分）",
                estimateCents, depreciation, estimateCents - depreciation,
                liabilityPercent, afterLiability, deductibleCents, afterDeductible, limitCents, payable));
        return r;
    }
}