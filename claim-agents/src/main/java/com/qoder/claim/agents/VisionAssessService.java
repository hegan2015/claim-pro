package com.qoder.claim.agents;

import com.qoder.claim.llm.LlmGatewayClient;
import com.qoder.claim.llm.LlmGatewayException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F2 视觉定损：损伤照片 → 多模态模型识别受损部件。
 * 一律经模型网关（doc-vision 端点，路由/脱敏/熔断/计量）；只识别部件，价格仍由价格库确定性给出。
 * 未配视觉端点 → AgentUnavailableException → 上层回退文本定损。
 */
@Service
public class VisionAssessService {

    private final LlmGatewayClient gateway;

    public VisionAssessService(LlmGatewayClient gateway) {
        this.gateway = gateway;
    }

    public String identify(String description, List<String> imageDataUrls) {
        if (!gateway.available("doc-vision")) {
            throw new AgentUnavailableException("doc-vision", "视觉端点未配置或熔断中");
        }
        try {
            LlmGatewayClient.Outcome out = gateway.chat("doc-vision", buildMessages(description, imageDataUrls), null, null);
            return out.getContent();
        } catch (LlmGatewayException e) {
            throw new AgentUnavailableException("doc-vision", e);
        }
    }

    static List<Map<String, Object>> buildMessages(String description, List<String> imageDataUrls) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content",
                "你是车险定损视觉专家。依据损伤照片识别受损部件与维修动作，只输出 JSON：" +
                        "{\"items\":[{\"partName\":\"部件标准名\",\"action\":\"REPAIR|REPLACE\",\"confidence\":0-1}],\"confidence\":整体置信度0-1}。" +
                        "部件名用标准名（如 前保险杠/左前大灯/引擎盖/前翼子板/左前车门）。不得估价，不得输出 JSON 以外内容。"));
        List<Map<String, Object>> parts = new ArrayList<>();
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "text");
        text.put("text", "事故描述：" + (description == null ? "" : description) + "。请识别照片中受损部件。");
        parts.add(text);
        for (String url : imageDataUrls) {
            parts.add(Map.of("type", "image_url", "image_url", Map.of("url", url)));
        }
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", parts);
        messages.add(user);
        return messages;
    }
}