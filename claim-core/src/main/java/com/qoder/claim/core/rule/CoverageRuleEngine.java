package com.qoder.claim.core.rule;

import org.kie.api.KieServices;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.StatelessKieSession;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 条款规则引擎（确定性，LLM 禁入）：Drools 规则包执行（rules/clause-2026-v1.drl）。
 * 判定语义与金标回归共用；输出命中条款/免责项，供核保解释 agent 翻译成客户可读文本。
 * 生产扩展：按出险日加载多版本规则包（ClauseRulePackages），接口不变。
 */
@Component
public class CoverageRuleEngine {

    private static final KieContainer CONTAINER = KieServices.get().getKieClasspathContainer();

    public CoverageDecision evaluate(PolicySnapshot policy, LocalDate accidentDate,
                                     String accidentType, boolean drunkDriving,
                                     boolean unlicensed, boolean intentional) {
        CoverageDecision d = new CoverageDecision();
        d.setClauseVersion(policy.getClauseVersion());
        d.setCovered(true);
        StatelessKieSession session = CONTAINER.newStatelessKieSession("clauseSession");
        session.getGlobals().set("decision", d);
        session.execute(List.of(policy, new AccidentFacts(accidentDate, accidentType, drunkDriving, unlicensed, intentional)));
        return d;
    }
}