package com.yeso.backend.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Random;

/**
 * 추첨처럼 확률로 결과를 고르는 코드가 주입받는 공용 {@link Random}. {@code new Random()}을 직접 만들면
 * 테스트가 결과를 통제할 수 없으므로, 이 bean을 통해서만 쓴다(docs/api/trip.md 3-7).
 */
@Configuration
public class RandomConfig {

    @Bean
    public Random random() {
        return new Random();
    }
}
