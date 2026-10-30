package com.yeso.backend.attraction.application.ranking;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 발행한 여행기를 다음 코스 추천에 반영하는 계산(docs/design/recommendation.md §5 "여행기 피드백").
 * ai {@code scripts/diary_feedback_eval.py}의 {@code both · asym}과 같은 식이다.
 *
 * <pre>
 * 점수 = z(순위 모델) + λ · w̄ · (z(Rocchio) + z(분류 lift)) / 2      (같은 지역 후보 안에서 표준화)
 *   Rocchio   = 후보 벡터 · 지난 여행 장소 벡터 중심(여행별 중심을 w로 가중 합한 뒤 정규화)
 *   분류 lift = log(p_u / p_0),  p_u = (지난 여행 장소의 그 중분류 수 + k·p_0) / (지난 여행 장소 수 + k)
 *               p_0 = 추천 가능 관광지 전체에서 그 중분류 비율 (empirical Bayes 수축)
 *   w(s)      = clip(0.5 + 0.25·(s − 3), 0, 1),  만족도가 없으면 s = 3
 * </pre>
 * 만족도는 여행 단위 값이라 장소마다 복사하지 않고 그 여행 전체의 가중치로만 쓴다. 불만족한 여행은 반영을 약하게 할 뿐
 * 장소를 밀어내지 않는다(비대칭). 여행기에 없는 정보(장소별 만족도·본문·사진)는 쓰지 않는다.
 */
public final class DiaryFeedback {

    public static final String VERSION = "diary-feedback-v1";

    /** 여행기 하나: 여행 단위 만족도(없으면 null)와 그 여행 최종 코스의 관광지. */
    public record Trip(Short satisfaction, List<Long> attractionIds) {
    }

    private DiaryFeedback() {
    }

    public static double weight(Short satisfaction) {
        double s = satisfaction == null ? 3 : satisfaction;
        return Math.max(0, Math.min(1, 0.5 + 0.25 * (s - 3)));
    }

    /**
     * @param base       지역 후보별 순위 모델 점수
     * @param trips      반영할 여행기(최근 것부터)
     * @param vectors    후보와 지난 여행 장소의 E5 벡터
     * @param categoryOf 관광지 중분류(lclsSystm2). 없으면 lift 0
     * @param prior      추천 가능 관광지 전체의 중분류 비율. 비었으면 분류 항을 쓰지 않는다
     */
    public static Map<Long, Double> adjust(Map<Long, Double> base, List<Trip> trips, Map<Long, float[]> vectors,
                                           Map<Long, String> categoryOf, Map<String, Double> prior,
                                           double lambda, double priorK) {
        if (base.isEmpty() || trips.isEmpty() || lambda <= 0) {
            return base;
        }
        int dim = vectors.values().stream().mapToInt(v -> v.length).max().orElse(0);
        double[] centroid = new double[dim];
        Map<String, Double> counts = new HashMap<>();
        double items = 0, weighted = 0;
        for (Trip trip : trips) {
            double w = weight(trip.satisfaction());
            double[] tripCentroid = new double[dim];
            int used = 0;
            for (Long id : trip.attractionIds()) {
                float[] v = vectors.get(id);
                if (v != null && v.length == dim) {
                    for (int i = 0; i < dim; i++) tripCentroid[i] += v[i];
                    used++;
                }
                String cat = categoryOf.get(id);
                if (cat != null && !cat.isEmpty()) counts.merge(cat, 1.0, Double::sum);
            }
            if (used > 0) {
                normalize(tripCentroid);
                for (int i = 0; i < dim; i++) centroid[i] += w * tripCentroid[i];
            }
            items += trip.attractionIds().size();
            weighted += w * trip.attractionIds().size();
        }
        if (items == 0) {
            return base;
        }
        normalize(centroid);
        double meanWeight = weighted / items;
        if (meanWeight <= 0) {
            return base;
        }
        List<Long> ids = List.copyOf(base.keySet());
        double[] b = new double[ids.size()], r = new double[ids.size()], c = new double[ids.size()];
        double n = items;   // 분류가 없는 장소도 분모에 넣는다(실험과 같다)
        for (int j = 0; j < ids.size(); j++) {
            Long id = ids.get(j);
            b[j] = base.get(id);
            float[] v = vectors.get(id);
            if (v != null && v.length == dim) {
                double dot = 0;
                for (int i = 0; i < dim; i++) dot += v[i] * centroid[i];
                r[j] = dot;
            }
            String cat = categoryOf.get(id);
            Double p0 = cat == null ? null : prior.get(cat);
            if (p0 != null && p0 > 0) {
                double pu = (counts.getOrDefault(cat, 0.0) + priorK * p0) / (n + priorK);
                c[j] = Math.log(pu / p0);
            }
        }
        double[] zb = z(b), zr = z(r), zc = z(c);
        Map<Long, Double> out = new HashMap<>();
        for (int j = 0; j < ids.size(); j++) {
            out.put(ids.get(j), zb[j] + lambda * meanWeight * (zr[j] + zc[j]) / 2);
        }
        return out;
    }

    static double[] z(double[] x) {
        double mean = 0;
        for (double v : x) mean += v;
        mean /= x.length;
        double var = 0;
        for (double v : x) var += (v - mean) * (v - mean);
        double sd = Math.sqrt(var / x.length);
        double[] out = new double[x.length];
        if (sd > 0) {
            for (int i = 0; i < x.length; i++) out[i] = (x[i] - mean) / sd;
        }
        return out;
    }

    private static void normalize(double[] v) {
        double norm = 0;
        for (double x : v) norm += x * x;
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < v.length; i++) v[i] /= norm;
        }
    }
}
