package com.qoder.claim.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MaskingServiceTest {

    private final MaskingService masking = new MaskingService();

    @Test
    void masksIdCard() {
        String out = masking.mask("身份证号110101199003077758，请核对");
        assertTrue(out.contains("[ID_MASKED]"));
        assertFalse(out.contains("110101199003077758"));
    }

    @Test
    void masksBankCardAndPhone() {
        String out = masking.mask("卡号6222021234567890123 手机13812345678");
        assertTrue(out.contains("[CARD_MASKED]"));
        assertTrue(out.contains("[PHONE_MASKED]"));
        assertFalse(out.contains("6222021234567890123"));
        assertFalse(out.contains("13812345678"));
    }

    @Test
    void keepsNormalNumbers() {
        String out = masking.mask("维修费 8200 元，案件号 CLM20261118001");
        assertEquals("维修费 8200 元，案件号 CLM20261118001", out);
    }

    @Test
    void nullSafe() {
        assertNull(masking.mask(null));
        assertEquals("", masking.mask(""));
    }
}