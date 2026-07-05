-- =====================================================================
-- V2__seed_quiz_problems.sql — 퀴즈 시드 데이터 (문제 8 + 객관식 보기 12)
-- =====================================================================
-- 왜 시드를 마이그레이션으로 넣나:
--   - MVP는 문제 등록 API를 만들지 않는다(docs/README). 데이터가 0건이면
--     퀴즈 API를 아예 시연할 수 없으므로 최소한의 샘플을 스키마와 함께 배포한다.
--   - Flyway에 넣으면 "문서와 마이그레이션만으로 프로젝트를 재현"한다는
--     프로젝트 원칙(docs/README)이 데이터까지 지켜진다.
--
-- 규칙(docs/01 "answer 컬럼 타입별 규칙"과 1:1):
--   - MULTIPLE_CHOICE : answer=NULL, 정답은 choice.is_correct 로 판정(정답 1개)
--   - OX              : answer='O' 또는 'X'
--   - SHORT_ANSWER    : 복수 정답을 '|' 구분(채점 시 trim+소문자 비교)
--   - ESSAY           : MVP 미사용 → 시드에 넣지 않는다
--
-- id를 명시하는 이유: 보기(choice)가 problem_id로 참조해야 하는데,
-- AUTO_INCREMENT에 맡기면 값이 환경마다 달라질 수 있다. V1 직후 빈 테이블에만
-- 적용되는 시드이므로 1..8 고정이 안전하고, 재현성도 좋아진다.
-- =====================================================================

INSERT INTO problem (id, domain, difficulty, type, question, answer, explanation, created_at) VALUES
    (1, 'NETWORK', 'BEGINNER', 'MULTIPLE_CHOICE',
     'TCP는 OSI 7계층 중 몇 계층 프로토콜인가?',
     NULL,
     'TCP/UDP는 전송(Transport, 4계층) 계층 프로토콜이다. 포트 번호로 프로세스를 식별하고, TCP는 연결·신뢰성·순서를 보장한다.',
     NOW(6)),
    (2, 'NETWORK', 'BEGINNER', 'OX',
     'UDP는 연결지향(connection-oriented) 프로토콜이다.',
     'X',
     'UDP는 비연결(connectionless) 프로토콜이다. 핸드셰이크 없이 바로 전송하므로 빠르지만, 전달·순서를 보장하지 않는다. 연결지향은 TCP.',
     NOW(6)),
    (3, 'NETWORK', 'INTERMEDIATE', 'SHORT_ANSWER',
     'IP 주소를 물리(MAC) 주소로 변환하는 프로토콜의 이름은? (영문 약어)',
     'arp|address resolution protocol',
     'ARP(Address Resolution Protocol). 같은 네트워크 안에서 "이 IP 가진 사람 MAC 좀 알려줘"라고 브로드캐스트로 묻고, 해당 호스트가 응답한다.',
     NOW(6)),
    (4, 'OS', 'BEGINNER', 'MULTIPLE_CHOICE',
     '같은 프로세스에 속한 스레드끼리 공유하지 않는 메모리 영역은?',
     NULL,
     '스택은 스레드마다 따로 가진다(각자의 함수 호출 흐름·지역변수를 담아야 하므로). 코드·데이터·힙 영역은 프로세스 안에서 공유된다.',
     NOW(6)),
    (5, 'OS', 'BEGINNER', 'OX',
     '일반적으로 스레드 간 컨텍스트 스위칭 비용은 프로세스 간 컨텍스트 스위칭보다 작다.',
     'O',
     '스레드는 주소 공간(페이지 테이블 등)을 공유하므로 전환 시 교체할 컨텍스트가 적고 캐시/TLB 무효화 부담도 작다. 프로세스 전환은 주소 공간까지 바뀐다.',
     NOW(6)),
    (6, 'DATABASE', 'BEGINNER', 'SHORT_ANSWER',
     '트랜잭션의 ACID 성질 중 A가 뜻하는 것은? (한글 또는 영문)',
     '원자성|atomicity',
     'Atomicity(원자성): 트랜잭션 안의 작업은 전부 성공하거나 전부 실패해야 한다. 절반만 반영된 상태(예: 출금만 되고 입금 안 됨)는 허용되지 않는다.',
     NOW(6)),
    (7, 'DATABASE', 'INTERMEDIATE', 'MULTIPLE_CHOICE',
     'MySQL InnoDB가 인덱스에 기본으로 사용하는 자료구조는?',
     NULL,
     'B+트리. 균형 트리라 검색이 O(log N)이고, 리프 노드가 연결 리스트로 이어져 범위 검색(BETWEEN, 정렬)에 특히 강하다. 해시 인덱스는 등호 검색만 가능하다.',
     NOW(6)),
    (8, 'DS_ALGORITHM', 'BEGINNER', 'OX',
     '퀵 정렬(Quick Sort)의 최악 시간복잡도는 O(n log n)이다.',
     'X',
     '퀵 정렬의 최악은 O(n²) — 피벗이 매번 최솟값/최댓값으로 뽑히는 경우(예: 정렬된 배열 + 첫 원소 피벗). 평균은 O(n log n)이다.',
     NOW(6));

-- 객관식 보기 (problem 1, 4, 7). MVP 규칙: 문제당 정답(is_correct=true) 보기는 정확히 1개.
INSERT INTO choice (problem_id, `text`, is_correct, seq) VALUES
    (1, '물리 계층',       FALSE, 1),
    (1, '전송 계층',       TRUE,  2),
    (1, '네트워크 계층',   FALSE, 3),
    (1, '응용 계층',       FALSE, 4),
    (4, '코드(텍스트) 영역', FALSE, 1),
    (4, '데이터 영역',     FALSE, 2),
    (4, '힙 영역',         FALSE, 3),
    (4, '스택 영역',       TRUE,  4),
    (7, '해시 테이블',     FALSE, 1),
    (7, 'B+트리',          TRUE,  2),
    (7, '이진 탐색 트리',  FALSE, 3),
    (7, '스킵 리스트',     FALSE, 4);
