package com.yeso.backend.attraction.application.ingestion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 명시적으로 활성화했을 때만 실행되는 재시작 가능한 one-shot 배치 진입점. */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "embedding.attraction-batch.enabled", havingValue = "true")
public class AttractionEmbeddingBatchRunner implements ApplicationRunner {

    private final AttractionEmbeddingBatchProcessor processor;

    @Override
    public void run(ApplicationArguments args) {
        int total = 0;
        int batch;
        while ((batch = processor.processNextBatch()) > 0) {
            total += batch;
        }
        log.info("attraction embedding batch finished processed={}", total);
    }
}
