package com.qoder.claim.common.rule;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DescriptionRulesTest {

    @Test
    void liabilityKeywords() {
        assertEquals(30, DescriptionRules.liabilityPercent("路口碰撞，本车次要责任"));
        assertEquals(50, DescriptionRules.liabilityPercent("双方同等责任"));
        assertEquals(70, DescriptionRules.liabilityPercent("交警认定主要责任"));
        assertEquals(100, DescriptionRules.liabilityPercent("追尾前车，本车全责"));
        assertEquals(100, DescriptionRules.liabilityPercent(null));
    }

    @Test
    void statutoryExclusionKeywords() {
        assertTrue(DescriptionRules.drunkDriving("交警认定酒驾"));
        assertTrue(DescriptionRules.drunkDriving("醉酒驾车"));
        assertFalse(DescriptionRules.drunkDriving("正常驾驶"));
        assertFalse(DescriptionRules.drunkDriving(null));
        assertTrue(DescriptionRules.unlicensed("无证驾驶期间"));
        assertFalse(DescriptionRules.unlicensed(null));
        assertTrue(DescriptionRules.intentional("故意制造事故"));
        assertFalse(DescriptionRules.intentional(null));
    }

    @Test
    void modelCodeExtraction() {
        assertEquals("VW-LAVIDA-2023", DescriptionRules.modelCode("大众朗逸2023款追尾"));
        assertEquals("TOYOTA-CAMRY-2024", DescriptionRules.modelCode("丰田凯美瑞2024款碰撞"));
        assertNull(DescriptionRules.modelCode("不明车型碰撞"));
        assertNull(DescriptionRules.modelCode(null));
    }

    @Test
    void claimedAmountExtraction() {
        assertEquals(6_800_000L, DescriptionRules.claimedAmountCents("前保险杠受损，索赔6.8万"));
        assertEquals(6_800_000L, DescriptionRules.claimedAmountCents("索赔金额 6.8 万元"));
        assertEquals(4_200_00L, DescriptionRules.claimedAmountCents("索赔4200元"));
        assertEquals(1_260_000L, DescriptionRules.claimedAmountCents("索赔1.26万，本车全责"));
        assertEquals(-1, DescriptionRules.claimedAmountCents("追尾前车，本车全责"));
        assertEquals(-1, DescriptionRules.claimedAmountCents(null));
    }
}