package com.qoder.claim.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayChatModelTest {

    private final GatewayChatModel model = new GatewayChatModel(
            new LlmGatewayClient(new LlmGatewayProperties(), new MaskingService()), "assess");
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void toolSchemaWithoutDescriptionsMustNotContainNull() throws Exception {
        ToolSpecification spec = ToolSpecification.builder()
                .name("queryPartPrice")
                .description("查询配件价格")
                .parameters(JsonObjectSchema.builder()
                        .addProperty("modelCode", JsonStringSchema.builder().build())
                        .addProperty("partName", JsonStringSchema.builder().build())
                        .required("modelCode", "partName")
                        .build())
                .build();

        List<Map<String, Object>> wire = model.toWireTools(List.of(spec));
        String out = json.writeValueAsString(wire);

        assertFalse(out.contains(":null"), "出域 schema 不得含 null 字段（DeepSeek 严格校验会 400）: " + out);
        assertFalse(out.contains("definitions"), "不得混入框架内部字段: " + out);
        assertTrue(out.contains("\"modelCode\":{\"type\":\"string\"}"), "参数必须带 type: " + out);
        assertTrue(out.contains("\"required\":[\"modelCode\",\"partName\"]"));
    }

    @Test
    void emptySpecsYieldNullTools() {
        org.junit.jupiter.api.Assertions.assertNull(model.toWireTools(List.of()));
        org.junit.jupiter.api.Assertions.assertNull(model.toWireTools(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void multimodalUserMessageProducesImageParts() throws Exception {
        dev.langchain4j.data.message.UserMessage um = dev.langchain4j.data.message.UserMessage.from(
                dev.langchain4j.data.message.TextContent.from("识别受损部件"),
                dev.langchain4j.data.message.ImageContent.from(
                        dev.langchain4j.data.image.Image.builder().base64Data("aGVsbG8=").mimeType("image/png").build()));
        List<Map<String, Object>> wire = model.toWireMessages(List.of(um));
        Map<String, Object> user = wire.get(0);
        assertTrue(user.get("content") instanceof List, "多模态 content 须为块数组");
        List<Map<String, Object>> parts = (List<Map<String, Object>>) user.get("content");
        assertTrue(parts.stream().anyMatch(p -> "text".equals(p.get("type"))));
        Map<String, Object> img = parts.stream().filter(p -> "image_url".equals(p.get("type"))).findFirst().orElseThrow();
        String url = (String) ((Map<String, Object>) img.get("image_url")).get("url");
        assertTrue(url.startsWith("data:image/png;base64,aGVsbG8="), "图像须为 base64 data URL: " + url);
    }
}
