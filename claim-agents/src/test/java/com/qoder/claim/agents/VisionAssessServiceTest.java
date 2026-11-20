package com.qoder.claim.agents;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VisionAssessServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void visionMessagesCarryTextAndImages() {
        List<Map<String, Object>> msgs = VisionAssessService.buildMessages("朗逸追尾",
                List.of("data:image/png;base64,AAA", "data:image/jpeg;base64,BBB"));
        assertEquals(2, msgs.size());
        assertEquals("system", msgs.get(0).get("role"));
        List<Map<String, Object>> parts = (List<Map<String, Object>>) msgs.get(1).get("content");
        assertEquals(3, parts.size()); // 1 文本 + 2 图像
        assertEquals("text", parts.get(0).get("type"));
        assertTrue(((String) parts.get(0).get("text")).contains("朗逸追尾"));
        assertEquals("image_url", parts.get(1).get("type"));
        assertEquals("data:image/png;base64,AAA",
                ((Map<String, Object>) parts.get(1).get("image_url")).get("url"));
    }
}