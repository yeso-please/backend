package com.yeso.backend.onboarding.infrastructure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "embedding")
@Getter
@Setter
public class EmbeddingProperties {

    /** Python 임베딩 서비스 base URL. 운영 profile에서 비어 있으면 안 되지만, 기동 자체를 막지는 않는다
     * — 실패는 job PENDING/FAILED로 흡수되고 submission 자체는 항상 성공해야 하기 때문이다. */
    private String baseUrl = "";
    private String modelVersion = "demo-embedding-v1";
    private int templateVersion = 1;
    private int expectedDimension = 384;
    private int timeoutMillis = 5000;
    private int maxAttempts = 5;
    private int retryBackoffSeconds = 30;
}
