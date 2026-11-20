package com.qoder.claim.core.rule;

import java.util.List;

/** 条款版本规则包选择：按保单条款版本加载对应 DRL（生产多版本并存，按出险日取版本）。 */
public final class ClauseRulePackages {

    public static final String DEFAULT_VERSION = "clause-2026-v1";

    private ClauseRulePackages() {
    }

    /** classpath 规则包路径；未知版本回退默认包并如实记录（不静默放行）。 */
    public static String resourceFor(String clauseVersion) {
        String v = clauseVersion == null || clauseVersion.isBlank() ? DEFAULT_VERSION : clauseVersion;
        String path = "rules/" + v + ".drl";
        if (ClauseRulePackages.class.getClassLoader().getResource(path) != null) {
            return path;
        }
        return "rules/" + DEFAULT_VERSION + ".drl";
    }

    public static List<String> knownVersions() {
        return List.of(DEFAULT_VERSION);
    }
}