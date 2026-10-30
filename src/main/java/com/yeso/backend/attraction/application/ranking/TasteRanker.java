package com.yeso.backend.attraction.application.ranking;

import com.yeso.backend.attraction.application.region.CourseMaterialService;
import com.yeso.backend.attraction.infrastructure.AttractionQueryRepository;
import com.yeso.backend.attraction.infrastructure.AttractionQueryRepository.RankingMetaRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 개인화 순위 점수(docs/design/recommendation.md §5). E5 유사도·설문·관광지 분류·설명 특성을 LightGBM 모델로 합친다.
 *
 * <p>모델 파일({@code ranker.model-path})이 없거나 E5 모델 버전이 다르면 꺼지고, 호출부는 기존 코사인 유사도를 쓴다.
 * 특성은 학습(ai {@code scripts/lgbm_ranker.py})과 같은 순서·같은 계산이다.
 */
@Slf4j
@Component
public class TasteRanker {

    /** 설문 스타일 1·3·5·6(1~7점)과 동기 코드(1~9). */
    public record Survey(Map<Integer, Integer> travelStyles, List<Integer> travelMotives) {
    }

    private static final int[] STYLES = {1, 3, 5, 6};
    private static final int MAX_DESCRIPTION_CHARS = 300;

    private final RankerModel model;
    private final CourseMaterialService courseMaterialService;
    private final AttractionQueryRepository attractionQueryRepository;
    private final Map<Long, RankingMetaRow> metaCache = new ConcurrentHashMap<>();
    /** 관광지 벡터의 PCA 투영. 벡터가 바뀌면(재임베딩) 다시 계산한다. */
    private final Map<Long, Projection> projectionCache = new ConcurrentHashMap<>();

    private record Projection(float[] source, double[] values) {
    }

    /**
     * 요청자별 점수. 같은 사람이 다시 뽑기·코스 생성을 하면 같은 관광지를 다시 계산하지 않는다. 설문·취향 벡터가
     * 바뀌면 키가 달라진다. 오래되면 버리고, 최근 요청자 {@value #MAX_REQUESTERS}명만 기억한다.
     */
    private static final int MAX_REQUESTERS = 256;
    private static final Duration SCORE_TTL = Duration.ofMinutes(10);
    private final Map<RequesterKey, RequesterScores> scoreCache = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<RequesterKey, RequesterScores> eldest) {
                    return size() > MAX_REQUESTERS;
                }
            });

    private record RequesterKey(List<Double> survey, List<Float> vector) {
        static RequesterKey of(double[] surveyValues, float[] vector) {
            List<Float> values = new ArrayList<>(vector.length);
            for (float value : vector) {
                values.add(value);
            }
            return new RequesterKey(Arrays.stream(surveyValues).boxed().toList(), List.copyOf(values));
        }
    }

    private record RequesterScores(Map<Long, Double> scores, Instant expiresAt) {
    }

    /** 여행기 피드백 가중치 λ. 0이면 끈다(ai reports/diary_feedback_v1.md에서 고른 값). */
    private final double diaryFeedbackWeight;
    private static final double DIARY_PRIOR_K = 5.0;
    /** 여행기 피드백을 적용할 회원 비율(무작위 배정, 0~1). 나머지는 대조군으로 노출 기록에만 남는다. */
    private final double diaryTreatmentShare;
    /** 추천 가능 관광지 전체의 중분류(lclsSystm2) 비율. 메타를 미리 읽은 뒤 채운다. */
    private volatile Map<String, Double> categoryPrior = Map.of();

    public TasteRanker(@Value("${ranker.model-path:}") String modelPath,
                       @Value("${embedding.model-version:}") String embeddingModelVersion,
                       @Value("${ranker.diary-feedback.weight:0.1}") double diaryFeedbackWeight,
                       @Value("${ranker.diary-feedback.treatment-share:0.5}") double diaryTreatmentShare,
                       CourseMaterialService courseMaterialService, AttractionQueryRepository attractionQueryRepository) {
        this.courseMaterialService = courseMaterialService;
        this.attractionQueryRepository = attractionQueryRepository;
        this.diaryFeedbackWeight = diaryFeedbackWeight;
        this.diaryTreatmentShare = diaryTreatmentShare;
        this.model = load(modelPath, embeddingModelVersion);
    }

    private static RankerModel load(String modelPath, String embeddingModelVersion) {
        if (modelPath == null || modelPath.isBlank()) {
            log.info("ranker disabled: ranker.model-path is empty (taste score falls back to cosine)");
            return null;
        }
        Path path = Path.of(modelPath);
        if (!Files.isRegularFile(path)) {
            log.warn("ranker disabled: model file not found at {}", path);
            return null;
        }
        try (InputStream input = Files.newInputStream(path)) {
            RankerModel loaded = RankerModel.read(input);
            if (!loaded.e5ModelVersion().equals(embeddingModelVersion)) {
                log.warn("ranker disabled: trained on E5 {} but embedding.model-version is {}",
                        loaded.e5ModelVersion(), embeddingModelVersion);
                return null;
            }
            log.info("ranker enabled: {} ({} features)", loaded.version(), loaded.features().size());
            return loaded;
        } catch (IOException | RuntimeException e) {
            log.warn("ranker disabled: cannot read {}: {}", path, e.toString());
            return null;
        }
    }

    /**
     * 관광지 순위 메타(분류·설명 길이)는 적재 배치 때만 바뀐다. 첫 추첨이 지역마다 따로 읽지 않도록 시작할 때 한 번에 읽어 둔다.
     * 실패해도 요청 때 필요한 것만 읽으면 되므로 경고만 남긴다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void preloadMeta() {
        if (model == null) {
            return;
        }
        Thread.ofVirtual().name("ranker-meta-preload").start(() -> {
            try {
                long started = System.nanoTime();
                List<RankingMetaRow> rows = attractionQueryRepository.findAllRecommendableRankingMeta();
                rows.forEach(row -> metaCache.put(row.attractionId(), row));
                Map<String, Double> prior = new HashMap<>();
                rows.forEach(row -> {
                    if (row.lclsSystm2() != null && !row.lclsSystm2().isEmpty()) {
                        prior.merge(row.lclsSystm2(), 1.0 / rows.size(), Double::sum);
                    }
                });
                categoryPrior = Map.copyOf(prior);
                log.info("ranker meta preloaded: {} attractions in {} ms", metaCache.size(),
                        (System.nanoTime() - started) / 1_000_000);
            } catch (RuntimeException e) {
                log.warn("ranker meta preload failed (loaded on demand instead): {}", e.toString());
            }
        });
    }

    /**
     * 회원 ID로 정해지는 고정 무작위 배정. 같은 회원은 항상 같은 쪽이고, 해시가 실험 버전마다 달라
     * 다음 실험과 배정이 겹치지 않는다.
     */
    public boolean diaryFeedbackTreated(Long userId) {
        long h = userId * 0x9E3779B97F4A7C15L ^ DiaryFeedback.VERSION.hashCode();
        h ^= h >>> 31;
        return Math.floorMod(h, 10_000) < Math.round(diaryTreatmentShare * 10_000);
    }

    public boolean diaryFeedbackEnabled() {
        return model != null && diaryFeedbackWeight > 0;
    }

    /**
     * 발행한 여행기로 순위 점수를 조정한다({@link DiaryFeedback}). 꺼져 있거나 반영할 여행기가 없으면 그대로 돌려준다.
     * 결과는 지역 후보 안에서 표준화된 값이라 원래 점수와 크기가 다르다(코스 생성은 0~1로 다시 늘인다).
     */
    public Map<Long, Double> applyDiaryFeedback(Map<Long, Double> scores, List<DiaryFeedback.Trip> trips) {
        if (!diaryFeedbackEnabled() || scores.isEmpty() || trips.isEmpty()) {
            return scores;
        }
        java.util.Set<Long> ids = new java.util.HashSet<>(scores.keySet());
        trips.forEach(trip -> ids.addAll(trip.attractionIds()));
        Map<Long, float[]> vectors = courseMaterialService.findAttractionVectors(ids);
        Map<Long, String> categoryOf = new HashMap<>();
        meta(ids).forEach((id, row) -> categoryOf.put(id, row.lclsSystm2()));
        return DiaryFeedback.adjust(scores, trips, vectors, categoryOf, categoryPrior, diaryFeedbackWeight, DIARY_PRIOR_K);
    }

    public boolean enabled() {
        return model != null;
    }

    /** 켜져 있으면 모델 버전, 아니면 빈 값. 노출 기록에 남긴다. */
    public Optional<String> version() {
        return Optional.ofNullable(model).map(RankerModel::version);
    }

    /**
     * 관광지별 순위 점수(클수록 추천). 꺼져 있거나, 설문·요청자 벡터가 없으면 빈 값.
     * 벡터가 없는 관광지는 결과에 없다.
     */
    public Optional<Map<Long, Double>> score(Survey survey, float[] requesterVector, Collection<Long> attractionIds) {
        if (model == null || survey == null || requesterVector == null || attractionIds.isEmpty()) {
            return Optional.empty();
        }
        double[] surveyValues = surveyValues(survey);
        Map<Long, Double> known = requesterScores(RequesterKey.of(surveyValues, requesterVector));
        Map<Long, Double> scores = new HashMap<>();
        List<Long> unscored = new ArrayList<>();
        for (Long id : attractionIds) {
            Double cached = known.get(id);
            if (cached != null) {
                scores.put(id, cached);
            } else {
                unscored.add(id);
            }
        }
        Map<Long, float[]> vectors = unscored.isEmpty() ? Map.of() : courseMaterialService.findAttractionVectors(unscored);
        Map<Long, RankingMetaRow> meta = meta(vectors.keySet());
        for (Map.Entry<Long, float[]> entry : vectors.entrySet()) {
            RankingMetaRow row = meta.get(entry.getKey());
            float[] vector = entry.getValue();
            if (row == null || vector.length != requesterVector.length) {
                continue;
            }
            double score = model.score(features(surveyValues, requesterVector, vector, row,
                    projection(entry.getKey(), vector)));
            scores.put(entry.getKey(), score);
            known.put(entry.getKey(), score);
        }
        return scores.isEmpty() ? Optional.empty() : Optional.of(scores);
    }

    private Map<Long, Double> requesterScores(RequesterKey key) {
        Instant now = Instant.now();
        synchronized (scoreCache) {
            RequesterScores cached = scoreCache.get(key);
            if (cached == null || !now.isBefore(cached.expiresAt())) {
                cached = new RequesterScores(new ConcurrentHashMap<>(), now.plus(SCORE_TTL));
                scoreCache.put(key, cached);
            }
            return cached.scores();
        }
    }

    private double[] projection(Long attractionId, float[] vector) {
        Projection cached = projectionCache.get(attractionId);
        if (cached == null || cached.source() != vector) {
            cached = new Projection(vector, model.project(vector));
            projectionCache.put(attractionId, cached);
        }
        return cached.values();
    }

    double[] features(double[] surveyValues, float[] user, float[] item, RankingMetaRow row, double[] projected) {
        double[] x = new double[model.features().size()];
        List<String> names = model.features();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            x[i] = switch (name) {
                case "e5_cos" -> cosine(user, item);
                case "contenttypeid" -> model.categoryCode(name, row.contentTypeId() == null ? "" : row.contentTypeId().toString());
                case "lclsSystm1" -> model.categoryCode(name, row.lclsSystm1());
                case "lclsSystm2" -> model.categoryCode(name, row.lclsSystm2());
                case "lclsSystm3" -> model.categoryCode(name, row.lclsSystm3());
                case "desc_chars" -> Math.min(row.descriptionChars(), MAX_DESCRIPTION_CHARS);
                default -> surveyOrProjection(name, surveyValues, projected);
            };
        }
        return x;
    }

    private static double surveyOrProjection(String name, double[] surveyValues, double[] projected) {
        if (name.startsWith("style_")) {
            int number = Integer.parseInt(name.substring(6));
            for (int i = 0; i < STYLES.length; i++) {
                if (STYLES[i] == number) {
                    return surveyValues[i];
                }
            }
        } else if (name.startsWith("motive_")) {
            return surveyValues[STYLES.length + Integer.parseInt(name.substring(7)) - 1];
        } else if (name.startsWith("desc_pc")) {
            return projected[Integer.parseInt(name.substring(7))];
        }
        throw new IllegalStateException("Unknown ranker feature: " + name);
    }

    /** 학습과 같다: 스타일은 (값 − 4) / 3(답이 없으면 중립 0), 동기 1~9는 고르면 1. */
    static double[] surveyValues(Survey survey) {
        double[] values = new double[STYLES.length + 9];
        for (int i = 0; i < STYLES.length; i++) {
            Integer value = survey.travelStyles() == null ? null : survey.travelStyles().get(STYLES[i]);
            values[i] = value == null ? 0 : (value - 4) / 3.0;
        }
        if (survey.travelMotives() != null) {
            for (Integer motive : survey.travelMotives()) {
                if (motive != null && motive >= 1 && motive <= 9) {
                    values[STYLES.length + motive - 1] = 1;
                }
            }
        }
        return values;
    }

    private Map<Long, RankingMetaRow> meta(Collection<Long> ids) {
        List<Long> missing = new ArrayList<>();
        for (Long id : ids) {
            if (!metaCache.containsKey(id)) {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            attractionQueryRepository.findRankingMeta(missing).forEach(row -> metaCache.put(row.attractionId(), row));
        }
        Map<Long, RankingMetaRow> result = new HashMap<>();
        for (Long id : ids) {
            RankingMetaRow row = metaCache.get(id);
            if (row != null) {
                result.put(id, row);
            }
        }
        return result;
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / Math.sqrt(leftNorm * rightNorm);
    }
}
