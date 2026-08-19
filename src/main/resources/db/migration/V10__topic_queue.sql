-- =====================================================================
-- V10__topic_queue.sql — 개념 문서 주제 대기열 (2026-08-19)
-- =====================================================================
-- [왜 테이블이 필요한가]
-- 주제 대기열은 원래 저장소 파일(generated/_topics.json) 하나였다. 배치가 클라우드에서
-- 도니까 DB를 볼 수 없어서인데, 그러다 보니 "주제를 하나 추가"하려면 JSON을 손으로
-- 고쳐야 했다. 쉼표 하나 빠뜨리면 그날 대기열이 통째로 안 읽히고, 그 사실은 배치가
-- 초록불로 끝나므로 며칠 뒤에나 드러난다.
--
-- 그래서 <입력은 관리자 화면(DB), 배치가 읽는 것은 파일>로 나눈다. 파일은 DB에서
-- 내보낸 사본이고(TopicQueueExporter), 배치가 파일에 찍은 사용 표시는 앱이 켜질 때
-- DB로 되돌아온다(TopicQueueSyncRunner). 기존 _existing-documents.json(내보내기)과
-- generated/*.json(흡수)이 이미 쓰던 구조 그대로라, 새로 배울 개념이 없다.
--
-- [시드를 넣지 않는다]
-- 콘텐츠는 Flyway에 넣지 않는 것이 이 저장소의 규칙이다(V2·V2_1·V3 삭제, 2026-08-12).
-- 지금 파일에 손으로 적어 둔 주제 8개는 첫 기동에 SyncRunner가 흡수한다 —
-- 마이그레이션에 INSERT를 넣는 것보다 이 경로가 낫다: 파일을 직접 고치는 사용법이
-- 계속 살아 있다는 것을 흡수 경로가 매번 증명하기 때문.
-- =====================================================================

CREATE TABLE topic_queue (
    id         BIGINT       NOT NULL AUTO_INCREMENT,

    -- 분야는 필수다. 문서의 분야가 이어지는 사흘치 문제의 분야까지 정하므로
    -- (DraftGeneratorCli.alignDomainWithDocument) 비워 두면 나흘이 통째로 엉킨다.
    domain     VARCHAR(30)  NOT NULL,

    -- 주제는 분야보다 좁게 적는다("스프링 트랜잭션"이 아니라 "@Transactional 전파 속성").
    -- 문서 제목(200)과 같은 길이로 맞춘다 — 주제가 곧 제목의 씨앗이다.
    topic      VARCHAR(200) NOT NULL,

    -- 왜 이 주제를 넣었는지. 배치는 읽지 않는다. 몇 주 뒤의 나를 위한 자리.
    memo       VARCHAR(500) NULL,

    -- 대기열 순서. UNIQUE를 걸지 않는 이유: 순서 바꾸기는 두 행의 값을 맞바꾸는 동작인데,
    -- UNIQUE가 있으면 중간 상태(둘 다 3)에서 걸려 임시값을 거치는 3단계 갱신이 필요해진다.
    -- 값이 겹쳐도 정렬 결과가 흔들릴 뿐 데이터가 깨지지 않는 종류라 제약을 걸 값이 없다.
    -- 'position'은 MySQL 함수 이름과 겹쳐 헷갈리므로 sort_order로 쓴다.
    sort_order INT          NOT NULL,

    -- 이 주제로 문서를 만든 날. 배치가 파일에 찍은 것이 기동 시 여기로 들어온다.
    -- NULL = 아직 대기 중. 다 쓴 항목을 지우지 않고 남기는 이유는 언제 무엇을 공부했는지가
    -- 그대로 학습 기록이 되기 때문(거절 사유를 남겨 두는 것과 같은 판단).
    used_at    DATE         NULL,

    created_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),

    -- 화면과 내보내기의 기본 조회가 "아직 안 쓴 것을 순서대로" 하나뿐이라 인덱스도 하나면 된다.
    KEY idx_topic_queue_used_order (used_at, sort_order)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
