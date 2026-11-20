package com.qoder.claim.core.rule;

import java.time.LocalDate;

/** 核保事实（Drools 规则包输入）：事故要素 + 法定免责事由标记。 */
public record AccidentFacts(LocalDate accidentDate, String accidentType,
                            boolean drunkDriving, boolean unlicensed, boolean intentional) {
}