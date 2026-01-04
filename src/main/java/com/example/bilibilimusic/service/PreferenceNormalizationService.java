package com.example.bilibilimusic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 偏好权重归一化服务
 *
 * 将累积的偏好权重归一化到 [0, 1] 或 [-1, 1] 区间，便于：
 * 1. 跨用户比较
 * 2. 多维度偏好融合
 * 3. 推荐算法稳定性
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PreferenceNormalizationService {

    /**
     * Min-Max 归一化到 [0, 1]
     *
     * @param weights 原始权重映射
     * @return 归一化后的权重映射
     */
    public Map<String, Double> normalizeMinMax(Map<String, Integer> weights) {
        if (weights == null || weights.isEmpty()) {
            return new HashMap<>();
        }

        // 找到最大值和最小值
        int minWeight = weights.values().stream().min(Integer::compareTo).orElse(0);
        int maxWeight = weights.values().stream().max(Integer::compareTo).orElse(0);

        if (minWeight == maxWeight) {
            // 所有权重相同，归一化为 0.5
            Map<String, Double> normalized = new HashMap<>();
            weights.forEach((key, value) -> normalized.put(key, 0.5));
            return normalized;
        }

        // Min-Max 归一化
        Map<String, Double> normalized = new HashMap<>();
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            double normalizedValue = (entry.getValue() - minWeight) / (double) (maxWeight - minWeight);
            normalized.put(entry.getKey(), normalizedValue);
        }

        return normalized;
    }

    /**
     * Z-Score 归一化（标准化）
     *
     * 适用于正态分布的权重数据，归一化到均值为0、标准差为1
     *
     * @param weights 原始权重映射
     * @return 归一化后的权重映射
     */
    public Map<String, Double> normalizeZScore(Map<String, Integer> weights) {
        if (weights == null || weights.isEmpty()) {
            return new HashMap<>();
        }

        // 计算均值
        double mean = weights.values().stream().mapToInt(Integer::intValue).average().orElse(0.0);

        // 计算标准差
        double variance = weights.values().stream()
            .mapToDouble(v -> Math.pow(v - mean, 2))
            .average()
            .orElse(0.0);
        double stdDev = Math.sqrt(variance);

        if (stdDev == 0) {
            // 标准差为0，所有值相同
            Map<String, Double> normalized = new HashMap<>();
            weights.forEach((key, value) -> normalized.put(key, 0.0));
            return normalized;
        }

        // Z-Score 归一化
        Map<String, Double> normalized = new HashMap<>();
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            double zScore = (entry.getValue() - mean) / stdDev;
            normalized.put(entry.getKey(), zScore);
        }

        return normalized;
    }

    /**
     * Softmax 归一化（概率分布）
     *
     * 将权重转换为概率分布，所有值之和为1，适用于多分类推荐
     *
     * @param weights 原始权重映射
     * @return 归一化后的权重映射（概率分布）
     */
    public Map<String, Double> normalizeSoftmax(Map<String, Integer> weights) {
        if (weights == null || weights.isEmpty()) {
            return new HashMap<>();
        }

        // 计算 exp(weight) 的总和（温度参数设为1）
        double sumExp = weights.values().stream()
            .mapToDouble(w -> Math.exp(w))
            .sum();

        if (sumExp == 0 || Double.isInfinite(sumExp)) {
            // 数值溢出，回退到均匀分布
            Map<String, Double> normalized = new HashMap<>();
            double uniform = 1.0 / weights.size();
            weights.forEach((key, value) -> normalized.put(key, uniform));
            return normalized;
        }

        // Softmax 归一化
        Map<String, Double> normalized = new HashMap<>();
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            double probability = Math.exp(entry.getValue()) / sumExp;
            normalized.put(entry.getKey(), probability);
        }

        return normalized;
    }

    /**
     * 对称归一化到 [-1, 1]（支持负向偏好）
     *
     * @param weights 原始权重映射（可能包含负值）
     * @return 归一化后的权重映射
     */
    public Map<String, Double> normalizeSymmetric(Map<String, Integer> weights) {
        if (weights == null || weights.isEmpty()) {
            return new HashMap<>();
        }

        // 找到绝对值最大值
        int maxAbsWeight = weights.values().stream()
            .mapToInt(Math::abs)
            .max()
            .orElse(1);

        if (maxAbsWeight == 0) {
            // 所有权重为0
            Map<String, Double> normalized = new HashMap<>();
            weights.forEach((key, value) -> normalized.put(key, 0.0));
            return normalized;
        }

        // 对称归一化
        Map<String, Double> normalized = new HashMap<>();
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            double normalizedValue = entry.getValue() / (double) maxAbsWeight;
            normalized.put(entry.getKey(), normalizedValue);
        }

        return normalized;
    }

    /**
     * 推荐默认归一化方法（考虑负向偏好的对称归一化）
     *
     * @param weights 原始权重映射
     * @return 归一化后的权重映射
     */
    public Map<String, Double> normalize(Map<String, Integer> weights) {
        return normalizeSymmetric(weights);
    }
}
