package com.yeso.backend.attraction.ingestion;

import com.yeso.backend.attraction.application.ingestion.IngestionWriterLock;
import com.yeso.backend.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionWriterLockIntegrationTest extends IntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("다른 적재 작업이 잠금을 보유하면 실행자 정보를 알리고 새 작업을 차단한다")
    void acquire_whenAnotherWriterHoldsLock_reportsWriterAndRejectsSecondWriter() throws Exception {
        UUID runId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO app.ingestion_runs(id, job_type, source_system, status, summary)
                     VALUES (?, 'TOURAPI_DETAIL_BACKFILL', 'TOUR_API', 'RUNNING',
                             '{"actor":"teammate","host":"dev-pc"}'::jsonb)
                     """)) {
            statement.setObject(1, runId);
            statement.executeUpdate();
        }

        try (Connection first = dataSource.getConnection();
             Connection second = dataSource.getConnection()) {
            first.setSchema("app");
            second.setSchema("app");
            try (IngestionWriterLock ignored = IngestionWriterLock.acquire(first, "TOURAPI_DETAIL_BACKFILL")) {
                assertThatThrownBy(() -> IngestionWriterLock.acquire(second, "DEMO_MIGRATION"))
                        .isInstanceOf(IngestionWriterLock.WriterBusyException.class)
                        .hasMessageContaining("teammate")
                        .hasMessageContaining("dev-pc")
                        .hasMessageContaining(runId.toString());
            }

            assertThatCode(() -> {
                try (IngestionWriterLock ignored = IngestionWriterLock.acquire(second, "DEMO_MIGRATION")) {
                    // Releasing the lease in this scope proves the lock can be acquired again.
                }
            }).doesNotThrowAnyException();
        }
    }
}
