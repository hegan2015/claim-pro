package com.qoder.claim.common.rule;

/**
 * 描述关键词的确定性映射：责任比例与法定免责事由。
 * 工具网关 / 编排管道 / 金标回归共用本类，保证重放与生产逻辑同源。
 */
public final class DescriptionRules {

    private DescriptionRules() {
    }

    public static int liabilityPercent(String description) {
        String desc = description == null ? "" : description;
        if (desc.contains("次要责任")) {
            return 30;
        }
        if (desc.contains("同等责任")) {
            return 50;
        }
        if (desc.contains("主要责任")) {
            return 70;
        }
        return 100;
    }

    public static boolean drunkDriving(String description) {
        String desc = description == null ? "" : description;
        return desc.contains("酒驾") || desc.contains("醉酒");
    }

    public static boolean unlicensed(String description) {
        return description != null && description.contains("无证");
    }

    public static boolean intentional(String description) {
        return description != null && description.contains("故意");
    }
}