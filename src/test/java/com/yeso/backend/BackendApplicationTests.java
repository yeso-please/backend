package com.yeso.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

// jwt.secret은 기본값이 없다(JwtProperties가 기동을 막음, 실제 배포 사고 방지) — 테스트 전용 값만 주입.
@SpringBootTest
@TestPropertySource(properties = "jwt.secret=test-only-secret-not-used-outside-automated-tests")
class BackendApplicationTests {
    @Test
    void contextLoads() {
    }
}
