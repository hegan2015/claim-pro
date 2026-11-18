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
}