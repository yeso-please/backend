package com.yeso.backend.shared.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "cors")
public class CorsProperties {

    /** credentials 요청을 허용할 정확한 Origin 목록. 와일드카드는 사용하지 않는다. */
    private List<String> allowedOrigins = List.of();
}
