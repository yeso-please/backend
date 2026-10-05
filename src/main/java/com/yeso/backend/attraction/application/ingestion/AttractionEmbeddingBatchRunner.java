package com.yeso.backend.attraction.application.ingestion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

/** 명시적으로 활성화했을 때만 실행되는 재시작 가능한 one-shot 배치 진입점. */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "embedding.attraction-batch.enabled", havingValue = "true")
public class AttractionEmbeddingBatchRunner implements ApplicationRunner {

    private final AttractionEmbeddingBatchProcessor processor;
    private final ConfigurableApplicationContext context;

    @Override
    public void run(ApplicationArguments args) {
        int total = 0;
        int batch;
        while ((batch = processor.processNextBatch()) > 0) {
            total += batch;
        }
        log.info("attraction embedding batch finished processed={}", total);
        // web-application-type=none으로 띄운 one-shot 실행은 끝나면 종료한다. 재시도 스케줄러가 JVM을 붙잡아 두기 때문이다.
        // 웹 서버와 함께 켠 경우에는 서버를 내리지 않는다.
        if (!(context instanceof WebApplicationContext)) {
            System.exit(SpringApplication.exit(context, () -> 0));
        }
    }
}
