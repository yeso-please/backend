package com.yeso.backend.attraction.application.ingestion;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Session-scoped PostgreSQL lock shared by bulk import/backfill writers.
 * The lock must be acquired and released on the same connection.
 */
public final class IngestionWriterLock implements AutoCloseable {

    private static final int LOCK_NAMESPACE = 0x54524950; // "TRIP"
    private static final int LOCK_ID = 0x494E4745; // "INGE"

    private final Connection connection;
    private final String jobType;
    private boolean closed;

    private IngestionWriterLock(Connection connection, String jobType) {
        this.connection = connection;
        this.jobType = jobType;
    }

    public static IngestionWriterLock acquire(Connection connection, String jobType) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?, ?)")) {
            statement.setInt(1, LOCK_NAMESPACE);
            statement.setInt(2, LOCK_ID);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw busy(connection);
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT set_config('application_name', ?, false)")) {
            statement.setString(1, "tripin-ingestion:" + jobType);
            statement.execute();
        }
        return new IngestionWriterLock(connection, jobType);
    }

    private static WriterBusyException busy(Connection connection) throws SQLException {
        String query = """
                SELECT activity.usename, activity.application_name, activity.client_addr,
                       run.id, run.job_type, run.summary ->> 'actor' AS actor,
                       run.summary ->> 'host' AS host, run.started_at, run.cursor_value
                FROM pg_locks lock
                JOIN pg_stat_activity activity ON activity.pid = lock.pid
                LEFT JOIN LATERAL (
                    SELECT id, job_type, summary, started_at, cursor_value
                    FROM app.ingestion_runs
                    WHERE status = 'RUNNING'
                      AND job_type = split_part(activity.application_name, ':', 2)
                    ORDER BY started_at DESC
                    LIMIT 1
                ) run ON TRUE
                WHERE lock.locktype = 'advisory'
                  AND lock.classid = ? AND lock.objid = ? AND lock.objsubid = 2
                LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, LOCK_NAMESPACE);
            statement.setInt(2, LOCK_ID);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return new WriterBusyException("다른 대량 적재 프로세스가 RDS 쓰기 잠금을 보유 중입니다. 잠시 후 다시 실행해 주세요.");
                }
                String actor = valueOr(result.getString("actor"), result.getString("usename"));
                String host = valueOr(result.getString("host"), valueOr(result.getString("client_addr"), "확인 불가"));
                String job = valueOr(result.getString("job_type"), result.getString("application_name"));
                UUID runId = (UUID) result.getObject("id");
                LocalDateTime startedAt = result.getObject("started_at", LocalDateTime.class);
                String cursor = result.getString("cursor_value");
                String message = "RDS 대량 적재가 이미 실행 중이라 새 실행을 차단했습니다."
                        + " 작업=" + job + ", 실행자=" + actor + ", 호스트=" + host
                        + (runId == null ? "" : ", runId=" + runId)
                        + (startedAt == null ? "" : ", 시작=" + startedAt)
                        + (cursor == null || cursor.isBlank() ? "" : ", 진행위치=" + cursor);
                return new WriterBusyException(message);
            }
        }
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?, ?)")) {
            statement.setInt(1, LOCK_NAMESPACE);
            statement.setInt(2, LOCK_ID);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new SQLException("RDS 적재 잠금을 현재 세션에서 해제하지 못했습니다. 작업 연결을 종료합니다.");
                }
            }
        }
    }

    public String jobType() {
        return jobType;
    }

    public static final class WriterBusyException extends SQLException {
        public WriterBusyException(String message) {
            super(message, "55P03");
        }
    }
}
