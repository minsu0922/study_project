-- =====================================================================
-- V7__imported_draft_file.sql — 생성 배치 결과 파일의 흡수 이력
-- =====================================================================
-- 기준 문서: docs/14-llm-batch-automation.md, 결정 기록: ADR-0006(개정)
--
-- 일일 생성 배치가 GitHub Actions로 옮겨가면서, 결과는 저장소의
-- generated/YYYY-MM-DD.json 파일로 도착한다. 로컬 앱은 기동할 때 아직 안 가져온
-- 파일을 찾아 검수 대기 초안(generated_problem_draft)으로 흡수하는데,
-- 이 테이블은 "어떤 파일을 이미 가져왔는가"를 기억해 중복 흡수를 막는다.
--
-- [왜 파일명을 PK로 쓰는가]
-- 파일명(2026-08-12.json)이 곧 그 배치의 신원이다. 대리키(AUTO_INCREMENT id)를 두고
-- filename에 UNIQUE를 거는 방법도 있지만, 그러면 "중복 방지"라는 이 테이블의 유일한
-- 목적이 PK가 아니라 부가 제약에 의존하게 된다. 자연키를 PK로 두면 중복 흡수가
-- DB 차원에서 불가능해진다 — 애플리케이션 검사(이미 가져왔나?)가 혹시 빠져나가도
-- INSERT가 실패하므로 이중 안전장치가 된다.
--
-- [왜 초안 테이블에 컬럼을 얹지 않았나]
-- generated_problem_draft에 source_file 컬럼을 추가하는 대안도 있었다. 하지만
-- "규약 위반으로 한 문제도 못 건진 파일"은 초안 행이 0개라 흔적이 남지 않아,
-- 다음 부팅에서 또 읽고 또 버리게 된다. 파일 단위 기록을 따로 두면 결과가
-- 0건이어도 "처리 완료"로 남길 수 있다.
-- =====================================================================

CREATE TABLE imported_draft_file (
    -- generated/ 아래의 파일명(디렉터리 제외). 예: '2026-08-12.json'
    filename    VARCHAR(100) NOT NULL,

    imported_at DATETIME(6)  NOT NULL,

    -- 그 파일에서 실제로 만들어진 초안 수. 모델이 규약을 어겨 걸러진 문제가 있으면
    -- 파일의 문제 수보다 적다. 프롬프트 품질을 사후에 되짚는 재료로 남긴다.
    draft_count INT          NOT NULL,

    PRIMARY KEY (filename)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
