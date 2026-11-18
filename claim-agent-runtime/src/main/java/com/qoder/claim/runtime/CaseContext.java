package com.qoder.claim.runtime;

/** 工具调用的案件上下文（ACL 依据），agent 执行期间由 facade 注入。 */
public final class CaseContext {

    private static final ThreadLocal<String> CASE_NO = new ThreadLocal<>();

    private CaseContext() {
    }

    public static void set(String caseNo) {
        CASE_NO.set(caseNo);
    }

    public static String get() {
        return CASE_NO.get();
    }

    public static void clear() {
        CASE_NO.remove();
    }
}