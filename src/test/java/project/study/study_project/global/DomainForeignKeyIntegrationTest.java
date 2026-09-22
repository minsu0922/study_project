package project.study.study_project.global;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 외래키(V20)가 실제로 걸려 있는지 — 5번 작업의 DB 쪽 최후 방어선.
 *
 * <p>서비스 계층의 {@code DomainCatalog.exists} 확인(각 쓰기 경로 테스트가 다룬다)은 <b>앱을
 * 거쳐 들어오는 요청</b>만 막는다. 콘솔에서 직접 SQL을 치거나 다른 배치가 같은 DB에 실수로
 * 쓰는 경로는 그 확인을 타지 않는다 — 그래서 DB 자체가 거부하는지를 여기서 따로 본다.
 */
@SpringBootTest
@Transactional
class DomainForeignKeyIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("다섯 표 모두 domain_setting을 외래키로 가리킨다")
    void foreignKeysExist() {
        List<String> tables = jdbc.queryForList("""
                SELECT TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = 'domain'
                  AND REFERENCED_TABLE_NAME = 'domain_setting'""", String.class);
        assertThat(tables).containsExactlyInAnyOrder(
                "problem", "document", "generated_problem_draft", "generated_document_draft", "topic_queue");
    }

    @Test
    @DisplayName("등록되지 않은 분야로 문제를 넣으면 DB가 거부한다 — 서비스 확인을 빠뜨려도 막히는 최후 방어선")
    void databaseRejectsUnknownDomain() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO topic_queue (domain, topic, sort_order, used_count, created_at) VALUES ('NOPE_X', 't', 0, 0, NOW(6))"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 쓰기 경로 8곳 각각의 "500이 아니라 400" 확인은 컨트롤러를 거치는 MockMvc 테스트가 맡는다
    // (DomainRegistryWritePathIntegrationTest) — 서비스만 호출하면 컨트롤러 배선을 거치지 않아
    // 500/400을 가를 수 없다(task-5-brief). 초안 흡수 2곳의 확인은 DraftImportServiceTest·
    // DocumentImportServiceTest의 rejectsFileWithUnregisteredDomain이 맡는다.
}
