package com.yeso.backend;

import com.yeso.backend.migration.DemoMigration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class DemoMigrationIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("tripin_test").withUsername("tripin_test").withPassword("tripin_test");

    @TempDir Path temp;

    @Test
    void tourApiOnly_dryRunApplyAndReapply() throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("app").defaultSchema("app").createSchemas(true).load().migrate();
        String source = temp.resolve("demo").toAbsolutePath().toString();
        try (var h2 = DriverManager.getConnection("jdbc:h2:file:" + source, "sa", ""); Statement st = h2.createStatement()) {
            st.execute("CREATE TABLE REGION(SIG_CD VARCHAR(5),PROVINCE VARCHAR(255),NAME VARCHAR(255),LAT DOUBLE,LNG DOUBLE)");
            st.execute("CREATE TABLE ATTRACTION(ID BIGINT,SOURCE_CONTENT_ID VARCHAR(255),SIG_CD VARCHAR(5),NAME VARCHAR(255),TYPE VARCHAR(255),DESCRIPTION TEXT,ADDR VARCHAR(255),LAT DOUBLE,LNG DOUBLE,IMAGE VARCHAR(255),HOMEPAGE TEXT,USETIME TEXT,RESTDATE VARCHAR(255),PARKING TEXT,INFOCENTER VARCHAR(255),TEL VARCHAR(255),DETAIL_FETCHED BOOLEAN,EVENT_START_DATE VARCHAR(8),EVENT_END_DATE VARCHAR(8))");
            st.execute("CREATE TABLE FOOD_PLACE(ID BIGINT,SOURCE_CONTENT_ID VARCHAR(255),SIG_CD VARCHAR(5),NAME VARCHAR(255),CATEGORY VARCHAR(255),DESCRIPTION TEXT,ADDR VARCHAR(255),LAT DOUBLE,LNG DOUBLE,IMAGE VARCHAR(255),USETIME TEXT,DETAIL_FETCHED BOOLEAN)");
            st.execute("CREATE TABLE TRAVEL_COURSE(ID BIGINT,SOURCE_CONTENT_ID VARCHAR(255),SIG_CD VARCHAR(5),TITLE VARCHAR(255),DESCRIPTION TEXT,THEME VARCHAR(255),TOTAL_DISTANCE VARCHAR(255))");
            st.execute("CREATE TABLE COURSE_POINT(COURSE_ID BIGINT,POINT_INDEX INTEGER,CONTENT_ID VARCHAR(255),NAME VARCHAR(255),TYPE VARCHAR(255),DESCRIPTION TEXT,IMAGE TEXT)");
            st.execute("CREATE TABLE APP_USER(ID BIGINT,EMAIL VARCHAR(255),PASSWORD VARCHAR(255))");
            st.execute("INSERT INTO APP_USER VALUES (1,'private@example.com','hash')");
            st.execute("INSERT INTO REGION VALUES ('11110','서울특별시','종로구',37.5,127.0)");
            st.execute("INSERT INTO ATTRACTION VALUES (1,'100','11110','관광지','관광지','설명','주소',37.5,127.0,'https://example.com/photo.jpg',NULL,NULL,NULL,NULL,NULL,NULL,TRUE,NULL,NULL)");
            st.execute("INSERT INTO FOOD_PLACE VALUES (2,'200','11110','식당','한식',NULL,'주소',37.5,127.0,NULL,NULL,FALSE)");
            // Cross the 200-row commit boundary for both batched target tables.
            for (int i = 0; i < 204; i++) {
                st.execute("INSERT INTO ATTRACTION(ID,SOURCE_CONTENT_ID,SIG_CD,NAME,TYPE,DETAIL_FETCHED) VALUES (" + (1000 + i) + ",'" + (10000 + i) + "','11110','관광지 " + i + "','관광지',FALSE)");
                st.execute("INSERT INTO FOOD_PLACE(ID,SOURCE_CONTENT_ID,SIG_CD,NAME,LAT,LNG,DETAIL_FETCHED) VALUES (" + (2000 + i) + ",'" + (20000 + i) + "','11110','식당 " + i + "',37.5,127.0,FALSE)");
            }
            st.execute("INSERT INTO TRAVEL_COURSE VALUES (3,'300','11110','공식 코스','설명','산책','3km')");
            st.execute("INSERT INTO COURSE_POINT VALUES (3,0,'100','관광지',NULL,'설명',NULL)");
        }
        String sourceFile = source + ".mv.db";
        String url = postgres.getJdbcUrl() + "?currentSchema=app";
        String user = postgres.getUsername(), password = postgres.getPassword();
        DemoMigration.run(new String[]{"--mode=dry-run", "--source=" + sourceFile}, url, user, password);
        assertThat(count(url, user, password, "regions")).isZero();
        UUID first = UUID.randomUUID();
        DemoMigration.run(new String[]{"--mode=apply", "--source=" + sourceFile, "--run-id=" + first}, url, user, password);
        assertThat(count(url, user, password, "regions")).isEqualTo(1);
        assertThat(count(url, user, password, "attractions")).isEqualTo(205);
        assertThat(count(url, user, password, "restaurants")).isEqualTo(205);
        assertThat(count(url, user, password, "official_courses")).isEqualTo(1);
        assertThat(count(url, user, password, "official_course_stops")).isEqualTo(1);
        try (var pg = DriverManager.getConnection(url, user, password); var st = pg.createStatement(); var rs = st.executeQuery("SELECT count(*) FROM app.official_course_stops WHERE attraction_id IS NOT NULL AND restaurant_id IS NULL")) {
            rs.next(); assertThat(rs.getInt(1)).isEqualTo(1);
        }
        assertThat(count(url, user, password, "users")).isZero();
        UUID second = UUID.randomUUID();
        DemoMigration.run(new String[]{"--mode=apply", "--source=" + sourceFile, "--run-id=" + second}, url, user, password);
        try (var pg = DriverManager.getConnection(url, user, password); var ps = pg.prepareStatement("SELECT inserted_count,updated_count FROM app.ingestion_runs WHERE id=?")) {
            ps.setObject(1, second);
            try (var rs = ps.executeQuery()) { assertThat(rs.next()).isTrue(); assertThat(rs.getInt(1)).isZero(); assertThat(rs.getInt(2)).isZero(); }
        }
        DemoMigration.run(new String[]{"--mode=validate", "--run-id=" + first}, url, user, password);
        try (var pg = DriverManager.getConnection(url, user, password); var ps = pg.prepareStatement("UPDATE app.ingestion_runs SET status='FAILED',cursor_value='REGION:1' WHERE id=?")) {
            ps.setObject(1, first); ps.executeUpdate();
        }
        DemoMigration.run(new String[]{"--mode=apply", "--source=" + sourceFile, "--resume-run-id=" + first}, url, user, password);
        assertThat(count(url, user, password, "attractions")).isEqualTo(205);

        try (var h2 = DriverManager.getConnection("jdbc:h2:file:" + source, "sa", ""); Statement st = h2.createStatement()) {
            st.execute("UPDATE ATTRACTION SET DESCRIPTION='변경된 설명' WHERE SOURCE_CONTENT_ID='100'");
            st.execute("UPDATE FOOD_PLACE SET DESCRIPTION='변경된 설명' WHERE SOURCE_CONTENT_ID='200'");
        }
        UUID changed = UUID.randomUUID();
        DemoMigration.run(new String[]{"--mode=apply", "--source=" + sourceFile, "--run-id=" + changed}, url, user, password);
        try (var pg = DriverManager.getConnection(url, user, password); var ps = pg.prepareStatement("SELECT inserted_count,updated_count FROM app.ingestion_runs WHERE id=?")) {
            ps.setObject(1, changed);
            try (var rs = ps.executeQuery()) { assertThat(rs.next()).isTrue(); assertThat(rs.getInt(1)).isZero(); assertThat(rs.getInt(2)).isEqualTo(2); }
        }
        UUID noOp = UUID.randomUUID();
        DemoMigration.run(new String[]{"--mode=apply", "--source=" + sourceFile, "--run-id=" + noOp}, url, user, password);
        try (var pg = DriverManager.getConnection(url, user, password); var ps = pg.prepareStatement("SELECT inserted_count,updated_count FROM app.ingestion_runs WHERE id=?")) {
            ps.setObject(1, noOp);
            try (var rs = ps.executeQuery()) { assertThat(rs.next()).isTrue(); assertThat(rs.getInt(1)).isZero(); assertThat(rs.getInt(2)).isZero(); }
        }
    }

    @Test
    void remoteAndProductionTargets_areRejectedBeforeConnecting() {
        assertThatThrownBy(() -> DemoMigration.run(new String[]{"--mode=dry-run", "--source=C:/missing.mv.db"},
                "jdbc:postgresql://tripin-prod.example.com/tripin_prod", "u", "p"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DemoMigration.run(new String[]{"--mode=dry-run", "--source=C:/missing.mv.db"},
                "jdbc:postgresql://tripin-dev.example.com/tripin_dev?sslmode=disable", "u", "p"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static int count(String url, String user, String password, String table) throws Exception {
        try (var pg = DriverManager.getConnection(url, user, password); var st = pg.createStatement(); var rs = st.executeQuery("SELECT count(*) FROM app." + table)) {
            rs.next(); return rs.getInt(1);
        }
    }
}
