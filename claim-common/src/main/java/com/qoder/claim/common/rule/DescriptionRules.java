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

    /** 从描述提取车型编码（价格库查询用）；未识别返回 null。 */
    public static String modelCode(String description) {
        String desc = description == null ? "" : description;
        if (desc.contains("朗逸")) {
            return "VW-LAVIDA-2023";
        }
        if (desc.contains("凯美瑞")) {
            return "TOYOTA-CAMRY-2024";
        }
        return null;
    }

    private static final java.util.regex.Pattern CLAIM_AMOUNT =
            java.util.regex.Pattern.compile("索赔(?:金额)?[^0-9]{0,8}(\\d+(?:\\.\\d+)?)\\s*(万|元|块)?");

    /** 从描述提取客户索赔金额（分）；未写明返回 -1。仅用于背离门禁，不参与理算。 */
    public static long claimedAmountCents(String description) {
        if (description == null) {
            return -1;
        }
        java.util.regex.Matcher m = CLAIM_AMOUNT.matcher(description);
        if (!m.find()) {
            return -1;
        }
        double v = Double.parseDouble(m.group(1));
        long unit = "万".equals(m.group(2)) ? 1_000_000L : 100L;
        return Math.round(v * unit);
    }
}