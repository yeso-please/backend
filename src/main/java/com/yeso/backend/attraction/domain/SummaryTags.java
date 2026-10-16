package com.yeso.backend.attraction.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 지역·관광지 태그 사전(#89, 2026-10-10 팀 확정). 사전에 없는 태그는 저장하지 않는다.
 * 바꾸면 V21의 DB 제약(ck_*_summaries_tags)과 ai {@code tripin_ai/summary/tags.py}를 함께 바꾼다.
 */
public final class SummaryTags {

    public static final List<String> DICTIONARY = List.of(
            "바다", "산", "숲", "호수·강", "섬", "꽃",
            "역사", "전통", "박물관", "사찰",
            "시장", "카페", "야경", "골목",
            "액티비티", "산책", "체험",
            "감성여행", "힐링", "가족여행", "데이트");
    public static final int MAX_TAGS = 4;

    private static final Set<String> ALLOWED = Set.copyOf(DICTIONARY);

    private SummaryTags() {
    }

    /** 앞뒤 공백과 {@code #}을 떼고 중복을 뺀다. 사전 밖 태그가 있거나 너무 많으면 예외. */
    public static List<String> normalize(Collection<String> tags) {
        Set<String> result = new LinkedHashSet<>();
        for (String raw : tags) {
            String tag = raw == null ? "" : raw.strip().replaceFirst("^#", "");
            if (tag.isEmpty()) {
                continue;
            }
            if (!ALLOWED.contains(tag)) {
                throw new IllegalArgumentException("태그 사전에 없는 태그입니다: " + tag);
            }
            result.add(tag);
        }
        if (result.size() > MAX_TAGS) {
            throw new IllegalArgumentException("태그는 최대 " + MAX_TAGS + "개입니다: " + result);
        }
        return new ArrayList<>(result);
    }

    /** 조회용. 저장된 값이 사전 밖이면(제약 이전 데이터 등) 조용히 뺀다. */
    public static List<String> filterKnown(Collection<String> tags) {
        return tags.stream().filter(ALLOWED::contains).distinct().limit(MAX_TAGS).toList();
    }
}
