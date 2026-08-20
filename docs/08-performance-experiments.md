# 08. 성능 실험 기록 — 인덱스 효과 실측 (로드맵 1 · 1부)

> 실험일: 2026-07-05 / 환경: 로컬 Windows, MySQL 8.0(InnoDB), Spring Boot 3.4.1
> 사용 스크립트: `db/perf/perf-data.sql`(제출 50만), `db/perf/perf-problems.sql`(문제 10만), `db/perf/perf-cleanup.sql`(원상복구)
> 측정 도구: `EXPLAIN ANALYZE`(실행 계획+실제 시간), `curl -w %{time_total}`(API 응답 전체 시간)

## 쉽게 말하면

지금까지 "인덱스를 걸었으니 빠를 것"이라고 **믿기만** 했다. 이번엔 가짜 데이터 50만 건을 넣고
인덱스를 뺐다 넣었다 하면서 **얼마나 차이 나는지 직접 쟀다**. 결과: 같은 화면이 인덱스 하나에
**16ms ↔ 1.8초**를 오간다. 책 뒤 색인을 찢어버리면 책 전체를 넘겨봐야 하는 것과 같다.

---

## 실험 1: 오답노트 조회 — 인덱스 유/무

**대상 쿼리**: `GET /api/me/wrong-answers` (제출 50만 건 테이블에서 내 오답을 문제당 최신 1건으로 집계)
**대상 인덱스**: `idx_submission_user_correct (user_id, is_correct, submitted_at)` — V1 설계 시 ADR-0002 근거로 미리 걸어둔 것

**데이터**: 회원 1,002명 / 제출 500,010건 (실사용자 제출 10건 포함)

| 측정 | 인덱스 있음 | 인덱스 없음 | 차이 |
|---|---|---|---|
| API 응답 시간 (워밍업 후) | **12~17ms** | **1,081~1,769ms** | **약 70~100배** |
| SQL 실행 시간 (EXPLAIN ANALYZE) | 0.55ms | 390ms | 약 700배 |
| 읽은 행 수 (바깥 쿼리) | **5행** (Index lookup) | **500,010행** (Table scan) | 10만 배 |
| 인덱스 재생성 시간 (50만 행) | 2.1초 | — | 운영 중 추가도 부담 적음 |

**실행 계획 결정적 한 줄**:
```
있음:  -> Index lookup on s using idx_submission_user_correct (user_id=1, is_correct=0) ... rows=5
없음:  -> Table scan on s ... rows=500010  → Sort: s.submitted_at DESC
```
인덱스가 있으면 (user_id, is_correct)로 곧장 내 오답 5행에 착지하고, 세 번째 컬럼(submitted_at)
덕분에 **정렬도 공짜**다. 없으면 50만 행 전부 읽고 걸러내고 정렬한다.

### 부수 발견: 인덱스가 FK의 버팀목이었다
`DROP INDEX`가 처음에 거부됐다 — `Cannot drop index: needed in a foreign key constraint`.
InnoDB는 FK 컬럼(user_id)에 인덱스를 요구하는데, 우리 복합 인덱스가 첫 컬럼이 user_id라서
**FK용 인덱스 역할을 겸하고 있었다**(별도 인덱스가 자동 생성되지 않은 이유).
실험을 위해 FK를 잠시 내리고 진행 후 둘 다 복구했다.
→ 교훈: 인덱스는 조회 성능만이 아니라 **제약조건의 기반**이기도 하다. 지울 때는 겸용 여부부터 확인.

---

## 실험 2: 퀴즈 무작위 추출 — ORDER BY RAND()의 성장통

**대상 쿼리**: `GET /api/quiz?size=10` (ProblemRepository의 `ORDER BY RAND() LIMIT n`)
코드 주석에 "데이터가 커지면 병목"이라고 예고해 뒀던 것을 실측.

| 문제 수 | API 응답 시간 | 비고 |
|---|---|---|
| 24개 (현재 운영) | **~12ms** | 전혀 문제 없음 |
| 100,024개 | **~180ms** | 15배. 아직 참을 만하지만 추세가 뚜렷 |

**실행 계획**:
```
-> Sort: rand(), limit input to 10 row(s) per chunk
    -> Table scan on p ... rows=100024   (전 행을 읽어 난수를 붙인 뒤 정렬)
```
LIMIT 10인데도 10만 행 전부에 rand()를 계산한다 — 이게 O(N log N)의 정체.
100만 건이면 초 단위로 진입할 추세다.

**개선 방향(로드맵)**: "랜덤 id 샘플링" — `MAX(id)` 사이 난수 id를 뽑아 `WHERE id >= :r LIMIT 1`을
n번 반복하거나 id 목록만 뽑아 IN 조회. 전 행 정렬이 사라진다.
단, **현재 규모(수백 문제)에선 12ms로 충분**하므로 지금 바꾸는 건 과최적화 — 측정으로
"언제 바꿔야 하는지"의 기준(문제 수만 건 이상)을 얻은 것이 이번 실험의 성과.

---

## 면접 대본 요약

"오답노트 조회에 (user_id, is_correct, submitted_at) 복합 인덱스를 설계 단계에서 걸었고,
제출 50만 건을 넣고 실측해 인덱스 유무로 API 응답이 16ms에서 1.8초로, 실행 계획이
5행 Index lookup에서 50만 행 Table scan으로 바뀌는 것을 확인했습니다. 세 번째 컬럼 덕분에
ORDER BY도 인덱스로 해결됩니다. 부수적으로, 이 인덱스가 FK 제약의 기반 인덱스를 겸하고 있어
DROP이 거부되는 것도 경험했습니다. 퀴즈의 ORDER BY RAND()는 10만 건에서 15배 느려지는 것을
확인해 개선 시점의 기준을 세웠지만, 현 규모에선 과최적화라 보류했습니다."

## 재현 방법

```bash
mysql -u csquiz -p csquiz < db/perf/perf-data.sql      # 제출 50만 생성 (~10초)
mysql -u csquiz -p csquiz < db/perf/perf-problems.sql  # 문제 10만 생성 (~2초)
# ... EXPLAIN ANALYZE / curl 측정 ...
mysql -u csquiz -p csquiz < db/perf/perf-cleanup.sql   # 전량 삭제·원상복구 (~25초)
```

---

# 로드맵 1 · 2부 — QueryDSL로 문서 목록 N+1 제거 (2026-07-05)

## 실험 3: 문서 목록 API의 쿼리 수 (요청 1회당)

**대상**: `GET /api/documents` (문서 9건 + 태그, LAZY 로딩)
**측정법**: hibernate SQL 로그(org.hibernate.SQL)에서 요청 구간의 쿼리 이벤트 수를 셈

| 구성 | 쿼리 수 | 비고 |
|---|---|---|
| 개선 전 + batch_fetch_size 없음 | **10** (1+9) | N+1의 원형 — 문서마다 태그 쿼리 1방씩 |
| 개선 전 + batch_fetch_size=100 (기존 운영) | 2 | IN 한 방으로 완화. 단 **설정 지우면 10으로 회귀**하는 잠복 구조 |
| **개선 후 (QueryDSL 프로젝션)** | **2 (설정 무관 고정)** | batch를 꺼도 2 — 구조적으로 N+1 불가능 |

## 덤으로 잡은 낭비: 본문(LONGTEXT) 불필요 전송

개선 전 목록 쿼리 SQL을 보면 `select ..., d1_0.content_md, ...` — **목록 DTO는 본문을 버리는데
DB는 매번 마크다운 본문 전체를 읽어 앱으로 보냈다**(엔티티 조회의 숨은 비용).
개선 후에는 select 절에 필요한 5개 컬럼만 있고 `content_md` 등장 0회.
문서가 수백 KB로 자라도 목록 API 페이로드는 영향받지 않는다.

## 어떻게 바꿨나 (DocumentRepositoryImpl)

1. **DTO 프로젝션**: 엔티티 대신 목록에 필요한 컬럼만 select (Projections.constructor)
2. **태그는 2방째에 IN 한 방**: 이 페이지 문서 id들로 태그를 모아 메모리에서 결합
3. **태그 필터는 join 대신 EXISTS**(`d.tags.any().name.in(...)`) — 행 복제가 없어
   distinct 불필요, 페이징 계산도 안 꼬임
4. **정렬은 화이트리스트 + id 보조 정렬** — 임의 속성 정렬 차단, 불안정 정렬로 인한
   페이지 중복/누락 방지
5. count는 PageableExecutionUtils — 셀 필요 없을 때(마지막 페이지 등) count 쿼리 생략

**왜 "컬렉션 fetch join + 페이징"을 안 썼나**: 그 조합은 Hibernate가 전체 결과를 메모리로
가져와 자르는 함정(HHH90003004)이 있다 — 조인으로 행이 (문서×태그)로 불어나 DB가
offset/limit을 못 자르기 때문. 표준 우회가 "id 페이징 → IN 조회" 2단계인데, 목록은 어차피
DTO라서 엔티티를 거치지 않는 프로젝션이 더 싸고 단순했다.

**삽질 기록**: 프로젝션 대상 record를 `private`으로 선언했더니 QueryDSL이 리플렉션으로
생성자를 못 찾아 "No constructor found" 500. → `public`으로 변경. (Projections.constructor는
public 생성자만 본다)

## 면접 대본 요약 (2부)

"문서 목록의 태그 로딩이 N+1 구조였습니다. batch_fetch_size로 완화돼 있었지만 설정 의존이라,
QueryDSL DTO 프로젝션 + 태그 IN 2단계 조회로 바꿔 어떤 설정에서도 쿼리 2방으로 고정했습니다.
실측으로 10방→2방을 확인했고, 부수적으로 엔티티 조회가 목록에서 버려지는 LONGTEXT 본문을
매번 읽던 낭비도 프로젝션으로 제거했습니다. 컬렉션 fetch join에 페이징을 걸면 인메모리
페이징이 되는 함정 때문에 fetch join 대신 이 패턴을 택했습니다."

## 남은 것 (로드맵 1 잔여)

- 대시보드 정답률 집계 쿼리도 대량 데이터에서 측정 (db/perf 스크립트 재사용)
