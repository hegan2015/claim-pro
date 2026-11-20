package com.qoder.claim.core.fraud;

import lombok.extern.slf4j.Slf4j;
import org.jpmml.evaluator.Evaluator;
import org.jpmml.evaluator.HasProbability;
import org.jpmml.evaluator.InputField;
import org.jpmml.evaluator.LoadingModelEvaluatorBuilder;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * XGBoost 欺诈评分（JPMML 加载 PMML）。演示模型由 deploy/model/train_fraud_model.py 合成样本训练，
 * 生产以真实标注案重训替换 fraud-model.pmml，特征/接口不变。
 * 加载失败不抛异常阻断启动：available()=false，由路由回退规则评分。
 */
@Slf4j
@Component
public class ModelFraudScorer implements FraudScoringStrategy {

    private volatile Evaluator evaluator;

    public ModelFraudScorer() {
        try (InputStream in = ModelFraudScorer.class.getResourceAsStream("/model/fraud-model.pmml")) {
            if (in == null) {
                log.warn("未找到 fraud-model.pmml，模型评分不可用（回退规则）");
                return;
            }
            this.evaluator = new LoadingModelEvaluatorBuilder().load(in).build();
            this.evaluator.verify();
            log.info("XGBoost 欺诈模型已加载（JPMML）");
        } catch (Exception e) {
            log.warn("XGBoost 模型加载失败（回退规则）: {}", e.getMessage());
        }
    }

    public boolean available() {
        return evaluator != null;
    }

    @Override
    public int score(long estimateCents, int priorClaims, boolean flaggedRepairShop, boolean lateNightReport) {
        Evaluator ev = evaluator;
        if (ev == null) {
            throw new IllegalStateException("模型不可用");
        }
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("estimateCents", estimateCents);
        raw.put("priorClaims", priorClaims);
        raw.put("flaggedRepairShop", flaggedRepairShop ? 1 : 0);
        raw.put("lateNightReport", lateNightReport ? 1 : 0);
        Map<String, Object> args = new LinkedHashMap<>();
        for (InputField in : ev.getInputFields()) {
            String key = in.getName();
            if (raw.containsKey(key)) {
                args.put(key, in.prepare(raw.get(key)));
            }
        }
        Map<String, ?> results = ev.evaluate(args);
        double p = extractFraudProbability(results);
        return (int) Math.round(Math.max(0, Math.min(1, p)) * 100);
    }

    private static double extractFraudProbability(Map<String, ?> results) {
        // 优先取显式 probability(1) 输出字段（PMML 分类结果类别键为 Integer，字符串查 HasProbability 会误返 0）
        for (Map.Entry<String, ?> e : results.entrySet()) {
            if ("probability(1)".equals(e.getKey()) && e.getValue() instanceof Number n) {
                return n.doubleValue();
            }
        }
        for (Object v : results.values()) {
            if (v instanceof HasProbability hp) {
                Double p1 = hp.getProbability(1);
                if (p1 == null) {
                    p1 = hp.getProbability("1");
                }
                if (p1 != null) {
                    return p1;
                }
            }
        }
        for (Object v : results.values()) {
            if (v instanceof Number n) {
                return n.doubleValue();
            }
        }
        return 0;
    }

    @Override
    public String name() {
        return "XGBOOST";
    }
}