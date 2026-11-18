package com.qoder.claim.core.rule;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 条款规则引擎（确定性，LLM 禁入）。
 * P0 为内置规则实现；生产替换为 Drools 规则包适配器（按出险日取条款版本），接口不变。
 * 输出命中条款/免责项，供核保解释 agent 翻译成客户可读文本。
 */
@Component
public class CoverageRuleEngine {

    public CoverageDecision evaluate(PolicySnapshot policy, LocalDate accidentDate,
                                     String accidentType, boolean drunkDriving,
                                     boolean unlicensed, boolean intentional) {
        CoverageDecision d = new CoverageDecision();
        d.setClauseVersion(policy.getClauseVersion());
        d.setCovered(true);

        if (accidentDate.isBefore(policy.getEffectiveDate()) || accidentDate.isAfter(policy.getExpiryDate())) {
            d.setCovered(false);
            d.getExclusions().add("EXCL-PERIOD");
            d.setExplanationKey("accident-outside-policy-period");
            return d;
        }
        d.getHitClauses().add("CLAUSE-PERIOD-OK");

        if (!policy.getCoverageTypes().contains(accidentType)) {
            d.setCovered(false);
            d.getExclusions().add("EXCL-TYPE");
            d.setExplanationKey("accident-type-not-covered");
            return d;
        }
        d.getHitClauses().add("CLAUSE-TYPE-OK");

        if (drunkDriving) {
            d.setCovered(false);
            d.getExclusions().add("EXCL-DRUNK");
        }
        if (unlicensed) {
            d.setCovered(false);
            d.getExclusions().add("EXCL-UNLICENSED");
        }
        if (intentional) {
            d.setCovered(false);
            d.getExclusions().add("EXCL-INTENTIONAL");
        }
        if (!d.isCovered()) {
            d.setExplanationKey("statutory-exclusion");
        }
        return d;
    }
}