package com.qoder.claim.llm;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** 出域脱敏：身份证/银行卡/手机号。先长后短，避免身份证被银行卡规则截断。 */
@Component
public class MaskingService {

    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = ID_CARD.matcher(text).replaceAll("[ID_MASKED]");
        out = BANK_CARD.matcher(out).replaceAll("[CARD_MASKED]");
        out = PHONE.matcher(out).replaceAll("[PHONE_MASKED]");
        return out;
    }
}