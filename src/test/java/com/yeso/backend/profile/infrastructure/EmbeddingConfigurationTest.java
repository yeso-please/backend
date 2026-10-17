package com.yeso.backend.profile.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(EmbeddingProperties.class)
    static class Config {}

    @Test
    @DisplayName("개발 RDS 프로필은 E5 768차원과 템플릿3을 기본으로 사용한다")
    void devRds_defaultsMatchSharedE5Vectors() {
        new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=dev-rds")
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Config.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(EmbeddingProperties.class);
                    assertThat(properties.getModelVersion()).isEqualTo("e5-base-rating-2022-2023-t4-all-v2");
                    assertThat(properties.getExpectedDimension()).isEqualTo(768);
                    assertThat(properties.getProfileTemplateVersion()).isEqualTo(3);
                    assertThat(properties.getTimeoutMillis()).isEqualTo(120000);
                    assertThat(context.getEnvironment().getProperty("embedding.attraction-batch.template-version", Integer.class))
                            .isEqualTo(3);
                });
    }
}
