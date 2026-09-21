-- V19__domain_setting.sql
-- 분야 설정 — 배치가 도는 분야·순환 순서와 화면 이름·경계 설명(docs/21).
--
-- [왜 행을 여기서 넣지 않나]
-- 마이그레이션에는 스키마만 둔다(docs/11). 더 큰 이유는 enum Domain에 상수를 더하거나
-- 뺐을 때다 — 시드를 여기 두면 그때마다 마이그레이션을 새로 써야 하고, 깜빡하면
-- 새 분야에 설정 행이 없어 배치에서 조용히 빠진다. 대신 기동 시
-- DomainSettingSyncRunner가 enum을 훑어 없는 행을 만들고 사라진 행을 지운다.
--
-- [왜 숫자 id가 없나]
-- 행 수가 enum 상수 수로 고정이고, 읽는 쪽이 늘 "NETWORK의 설정"을 찾지 "3번 행"을
-- 찾지 않는다. enum 이름을 그대로 PK로 쓰면 조인 없이도 뜻이 읽힌다.
CREATE TABLE domain_setting (
    domain       VARCHAR(30) NOT NULL,                 -- enum Domain 상수명
    enabled      BOOLEAN     NOT NULL DEFAULT TRUE,    -- 배치 자동 선택 후보인가
    sort_order   INT         NOT NULL,                 -- 날짜 순환 순서
    display_name VARCHAR(40) NOT NULL,                 -- 화면에 뜨는 이름
    hint         TEXT        NULL,                     -- 모델에게 주는 경계 설명
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (domain)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
