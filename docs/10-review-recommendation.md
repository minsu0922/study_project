# 10. 복습 추천 (망각곡선) — 로드맵 4

> 결정 기록: [ADR-0004](adr/0004-review-item-interval-ladder.md) (ReviewItem 분리 + 고정 간격 사다리)
> 선행 문서: [ADR-0002](adr/0002-wrong-answers-query-based.md) — 여기 적힌 재검토 트리거를 이행하는 단계다.

## 쉽게 말하면

사람의 기억은 시간이 지나면 흐려진다. 배운 직후엔 다 기억나지만 하루만 지나도 절반쯤
사라진다 — 이 곡선을 **망각곡선**(에빙하우스)이라고 한다. 📉

그런데 **잊어버리기 직전에 한 번 다시 보면**, 곡선이 다시 위로 올라가고 이번엔 더 천천히
떨어진다. 복습할 때마다 "잊는 속도"가 느려지니, 복습 간격을 점점 벌려도 된다.
그래서 가장 효율적인 복습은 "매일 전부 다시 보기"가 아니라
**"1일 뒤 → 3일 뒤 → 7일 뒤 → …" 식으로 간격을 늘려 가며 보기**다(간격 반복, spaced repetition).

이 기능은 그 타이밍 계산을 시스템이 대신 해 주는 것이다:
**틀린 문제를 기억해 뒀다가, 다시 풀어볼 때가 된 문제만 골라 "오늘의 복습"으로 보여준다.**

## 규칙 (간격 사다리)

문제마다 사용자별로 **사다리의 몇 번째 칸(stage)에 있는지**를 저장한다.

| stage | 0 | 1 | 2 | 3 | 4 | (통과) |
|---|---|---|---|---|---|---|
| 다음 복습까지 | 1일 | 3일 | 7일 | 14일 | 30일 | 졸업 🎓 |

- **틀리면** → stage 0으로 (리셋). 내일 다시 만난다.
- **맞히면** → 다음 칸으로 승급. 다음 복습일이 그만큼 멀어진다.
- **stage 4에서 맞히면** → 졸업(`GRADUATED`). 더는 추천에 안 나온다.
- **졸업한 문제를 나중에 또 틀리면** → 다시 stage 0부터. (기억은 영구 보증이 아니다)
- 한 번도 틀린 적 없는 문제는 사다리에 올라오지 않는다 — 복습할 이유가 없으므로.

간격 표는 서비스의 상수(`int[] INTERVALS = {1, 3, 7, 14, 30}`)로 두고, 값 변경은 상수 수정으로
끝낸다(알고리즘 교체가 아니므로 ADR 재검토 대상 아님 — ADR-0004).

### 설계 포인트: "미복습(due)"은 저장하지 않는다

ADR-0002에 예시로 적었던 상태 3종(미복습/복습중/완료) 중 "미복습(복습일이 지났는데 아직 안 품)"은
**컬럼으로 저장하지 않는다**. `next_review_at <= 지금` 이라는 시간 비교로 파생되는 값이기
때문이다. 이걸 컬럼으로 저장하면 매일 자정에 배치를 돌려 갱신해야 하는 상태가 생긴다 —
**시간이 흐르면 저절로 바뀌는 값은 저장하지 말고 조회 시점에 계산한다**는 원칙의 실전 사례.
그래서 저장하는 상태는 `LEARNING`(복습중) / `GRADUATED`(졸업) 2가지뿐이다.

---

## 데이터 모델 — `ReviewItem`

사용자 × 문제당 **딱 1행**. 이력이 아니라 **현재 상태**다(이력은 계속 Submission이 담당).

| 컬럼 | 타입 | 제약 | 비고 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | |
| user_id | BIGINT | FK → User(id), NOT NULL | |
| problem_id | BIGINT | FK → Problem(id), NOT NULL | |
| stage | INT | NOT NULL, 기본 0 | 사다리 칸 (0..4) |
| status | VARCHAR(15) | NOT NULL | enum `LEARNING` / `GRADUATED` |
| review_count | INT | NOT NULL, 기본 0 | 사다리에 오른 뒤 푼 횟수 (통계용) |
| next_review_at | DATETIME(6) | NOT NULL | 다음 복습 예정 시각 |
| created_at | DATETIME(6) | NOT NULL | 처음 틀린 시각 |
| updated_at | DATETIME(6) | NOT NULL | |

- **UNIQUE `uk_reviewitem_user_problem (user_id, problem_id)`** — "사용자×문제당 1행"을
  코드가 아니라 DB가 보장한다. 동시 제출(더블클릭 등)로 upsert가 경합해도 중복 행이 생길 수 없다.
- **인덱스 `idx_reviewitem_user_due (user_id, status, next_review_at)`** — "오늘의 복습" 쿼리
  (`WHERE user_id=? AND status='LEARNING' AND next_review_at <= NOW() ORDER BY next_review_at`)가
  이 인덱스 하나로 필터+정렬까지 끝나도록 등치(user_id, status) → 범위(next_review_at) 순서로 구성.
- FK는 둘 다 `ON DELETE CASCADE` — Submission(RESTRICT, 이력 보존)과 다른 이유:
  ReviewItem은 이력이 아니라 파생 가능한 "진행 상태"라, 문제가 삭제되면 함께 사라지는 게 맞다.
- `user_id`는 Submission과 같은 이유로 연관관계 없이 Long 컬럼. `problem`은 추천 목록에서
  지문·보기를 보여줘야 하므로 `@ManyToOne(LAZY)` 연관.

### 마이그레이션: `V4__review_item.sql`

테이블 생성 + **기존 오답 백필(backfill)**을 함께 한다.

- 백필 대상: 사용자×문제별 **최신 제출이 오답**인 것 → `stage 0, status LEARNING, next_review_at = NOW()`.
  (MySQL 8 윈도우 함수 `ROW_NUMBER() OVER (PARTITION BY user_id, problem_id ORDER BY submitted_at DESC)`로 최신 1건 추출)
- "틀린 적은 있지만 최근에 다시 맞힌" 문제는 제외 — 이미 스스로 복습을 마친 셈이므로.
  과거 이력 전체를 사다리로 리플레이하는 건 정확하지도(당시엔 규칙이 없었다) 단순하지도 않아서 안 한다.

---

## 상태 전이 — 쓰기 경로는 제출 API 하나뿐

별도 "복습 제출" API를 만들지 않고 **기존 `POST /api/quiz/submit`을 재사용**한다.
채점 경로가 하나면 ReviewItem 갱신 규칙도 한 곳에만 존재한다(정합성 관리 지점 최소화).
`QuizService.submit()`이 Submission 저장 후 같은 트랜잭션에서 ReviewService에 위임:

| 채점 결과 | ReviewItem 없음 | LEARNING | GRADUATED |
|---|---|---|---|
| **오답** | 생성 (stage 0, 내일) | stage 0 리셋 | `LEARNING`으로 복귀 + stage 0 |
| **정답** | 아무것도 안 함 | stage+1 (4였으면 졸업) | 그대로 |

- **같은 트랜잭션인 이유**: "채점·이력은 남았는데 복습 상태만 안 바뀐" 어중간한 상태 방지.
  Submission 저장과 원자적으로 묶는다.
- **트레이드오프(의도된 단순화)**: 복습일이 되기 *전에* 맞혀도 승급한다. 엄격한 간격 반복은
  예정일 전 풀이를 무시하지만, "일부러 미리 푼 사용자를 승급 안 시키는" 동작이 더 이상하다고
  판단. 규칙이 단순할수록 사용자가 시스템을 예측할 수 있다.
- `review_count`는 사다리에 있는 동안 제출할 때마다 +1 (졸업까지 몇 번 걸렸는지 통계용).

---

## API

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| GET | `/api/me/reviews/today` | 🔒 | 오늘 복습할 문제 (풀이용 — 정답/해설 미포함) |
| GET | `/api/me/reviews` | 🔒 | 내 복습 현황 전체 (stage·다음 복습일·상태) |

### GET /api/me/reviews/today

`status=LEARNING AND next_review_at <= 지금` 인 문제를 **오래 밀린 순**(next_review_at ASC)으로 페이징.

**Query params**: `page`(기본 0), `size`(기본 20, 최대 50)

**Response 200** (`data`는 페이지 구조)
```json
{
  "content": [
    {
      "problemId": 42, "domain": "NETWORK", "domainLabel": "네트워크",
      "difficulty": "INTERMEDIATE", "type": "MULTIPLE_CHOICE",
      "question": "TCP 3-way handshake의 순서로 옳은 것은?",
      "choices": [ { "id": 170, "text": "SYN → SYN+ACK → ACK", "seq": 1 } ],
      "stage": 1, "nextReviewAt": "2026-07-07T09:00:00", "reviewCount": 2
    }
  ],
  "page": 0, "size": 20, "totalElements": 3, "totalPages": 1, "hasNext": false
}
```
- 문제 부분은 `GET /api/quiz`의 풀이용 형태와 동일(정답·해설 없음 — 복습도 "풀어 보는" 것이므로).
  거기에 복습 메타(stage 등)만 얹는다.
- 답 제출은 `POST /api/quiz/submit` 그대로 사용.
- 빈 목록은 에러가 아니라 정상(오늘 복습할 게 없음) — 새 에러 코드 불필요.

### GET /api/me/reviews

전체 복습 현황(졸업 포함). 대시보드/진척 확인용. `status` 필터(선택), 기본 정렬 `nextReviewAt,asc`.

```json
{
  "content": [
    { "problemId": 42, "question": "TCP 3-way ...", "domain": "NETWORK", "domainLabel": "네트워크",
      "stage": 1, "status": "LEARNING", "reviewCount": 2,
      "nextReviewAt": "2026-07-10T09:00:00", "due": false }
  ], "page": 0, "size": 20, "totalElements": 8, "totalPages": 1, "hasNext": false
}
```
- `due`(지금 풀 때가 됐는지)는 저장된 값이 아니라 응답 조립 시 계산(위 "저장하지 않는다" 원칙).

### 오답노트와의 관계

`GET /api/me/wrong-answers`는 **그대로 유지**. 역할이 다르다:
오답노트 = "내가 뭘 틀렸고 뭐라고 답했나" (이력 뷰) / 복습 추천 = "오늘 뭘 다시 풀까" (스케줄 뷰).

---

## 구현 순서

1. `V4__review_item.sql` — 테이블 + 백필
2. `review` 패키지: `ReviewItem` 엔티티 + `ReviewStatus` enum + `ReviewItemRepository`
3. `ReviewService` 상태 전이 + `QuizService.submit()` 훅 연결
4. `GET /api/me/reviews/today` · `GET /api/me/reviews` + DTO
5. 테스트: 상태 전이 단위 테스트(표의 6칸 전부) + 통합 테스트(틀림→내일 등장→맞힘→간격 증가→졸업)
6. 문서 갱신 (03-api-spec에 API 2개 추가, README 로드맵 체크)

## 남은 것 / 다음 후보

- 자가 평가 UI 도입 시 SM-2 전환 검토 (ADR-0004 재검토 트리거)
- "오늘의 복습 N개" 배지용 카운트 API 또는 응답 필드
- 복습 리마인드 알림(이메일 등) — 외부 발송이라 별도 논의 필요
