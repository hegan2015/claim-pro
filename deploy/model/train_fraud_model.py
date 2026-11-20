#!/usr/bin/env python3
"""训练欺诈评分 XGBoost 演示模型并导出 PMML（供 Java JPMML 加载）。

⚠️ 演示模型：用合成样本训练，仅验证「模型评分适配器」链路（JPMML 加载/特征对齐/打分）。
生产须以核赔师标注的真实欺诈案件替换训练集重训，接口与特征不变。

特征与规则评分器 FraudScorer 完全对齐：
  estimateCents（估损，分）/ priorClaims（历史出险次数）/ flaggedRepairShop（0/1）/ lateNightReport（0/1）
输出：fraudProbability（0-1），Java 侧 ×100 取整为 riskScore。

运行：uv venv /tmp/ml-venv --python 3.12 && uv pip install --python /tmp/ml-venv/bin/python \
        xgboost scikit-learn sklearn2pmml pandas -i https://mirrors.aliyun.com/pypi/simple/
      DYLD_FALLBACK_LIBRARY_PATH=<sklearn/.dylibs> /tmp/ml-venv/bin/python deploy/model/train_fraud_model.py
"""
import os
import numpy as np
import pandas as pd
from xgboost import XGBClassifier
from sklearn2pmml import PMMLPipeline, sklearn2pmml

RNG = np.random.default_rng(42)
N = 20000

estimate = RNG.choice([0, 2000_00, 4200_00, 9000_00, 15000_00, 30000_00, 60000_00, 120000_00], N)
prior = RNG.integers(0, 7, N)
flagged = RNG.integers(0, 2, N)
late = RNG.integers(0, 2, N)

# 合成标签：以规则评分为基线概率 + 噪声，让模型学到与规则相近但平滑的决策面
base = np.zeros(N)
base += np.where(estimate > 50000_00, 0.25, np.where(estimate > 10000_00, 0.10, 0.0))
base += np.minimum(0.30, prior * 0.10)
base += flagged * 0.30
base += late * 0.10
prob = np.clip(base + RNG.normal(0, 0.05, N), 0.01, 0.99)
label = (RNG.random(N) < prob).astype(int)

df = pd.DataFrame({
    "estimateCents": estimate.astype(np.int64),
    "priorClaims": prior.astype(np.int64),
    "flaggedRepairShop": flagged.astype(np.int64),
    "lateNightReport": late.astype(np.int64),
})
pipeline = PMMLPipeline([
    ("classifier", XGBClassifier(
        n_estimators=120, max_depth=4, learning_rate=0.1,
        objective="binary:logistic", eval_metric="logloss",
        base_score=0.5, random_state=42)),
])
pipeline.fit(df, label)

out = os.path.join(os.path.dirname(__file__), "..", "..",
                   "claim-core", "src", "main", "resources", "model", "fraud-model.pmml")
out = os.path.abspath(out)
sklearn2pmml(pipeline, out, with_repr=True)
print("PMML 已导出:", out)
print("特征:", list(df.columns))
print("训练样本:", N, "正例率:", round(label.mean(), 3))