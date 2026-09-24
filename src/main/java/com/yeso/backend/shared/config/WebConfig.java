package com.yeso.backend.shared.config;

import com.yeso.backend.shared.web.CurrentUserArgumentResolver;
import com.yeso.backend.shared.web.CurrentUserIdArgumentResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 도메인별 argument resolver를 MVC에 연결하는 web 계층 배선이다 — shared가 도메인 로직을 갖는 게
 * 아니라, 각 도메인이 만든 resolver를 등록만 한다(docs/conventions/모듈-의존성.md).
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUserArgumentResolver;
    private final CurrentUserIdArgumentResolver currentUserIdArgumentResolver;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
        resolvers.add(currentUserIdArgumentResolver);
    }
}
