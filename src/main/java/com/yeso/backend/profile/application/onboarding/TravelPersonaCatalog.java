package com.yeso.backend.profile.application.onboarding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 여행 성향 16유형 콘텐츠(D4, {@code resources/personas/travel-personas.json}, 팀 검수 전 초안). 화면은 같은 파일을 프론트
 * 상수로 쓰고(D4-3), 백엔드는 지역 맞춤 문장(7-6)의 성향 이름·요약을 만들 때만 읽는다.
 */
@Component
public class TravelPersonaCatalog {

    static final String RESOURCE = "personas/travel-personas.json";

    public record Persona(String code, String name, String oneLiner, String description, List<String> hashtags,
                          String pace, List<String> places, List<String> activities, List<String> companions,
                          String characterKey) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Catalog(String version, List<Persona> types) {
    }

    private final Map<String, Persona> byCode;

    public TravelPersonaCatalog(JsonMapper jsonMapper) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            Catalog catalog = jsonMapper.readValue(in, Catalog.class);
            this.byCode = catalog.types().stream().collect(Collectors.toUnmodifiableMap(Persona::code, Function.identity()));
        } catch (IOException e) {
            throw new UncheckedIOException("성향 콘텐츠를 읽지 못했습니다: " + RESOURCE, e);
        }
    }

    public Optional<Persona> find(String mbtiCode) {
        return Optional.ofNullable(mbtiCode == null ? null : byCode.get(mbtiCode));
    }

    public int size() {
        return byCode.size();
    }
}
