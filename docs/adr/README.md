# ADR (Architecture Decision Records)

기술 결정을 기록하는 곳. **학습 문서이자 면접 대본**으로 쓴다.
새 결정은 `0000-template.md`를 복사해 번호를 붙여 작성한다.

## 목록
| 번호 | 제목 | 상태 | 로드맵 |
|---|---|---|---|
| [0001](0001-access-token-only-for-mvp.md) | MVP는 access 토큰만, refresh는 Redis 레이어로 유예 | 채택됨 | MVP / 로드맵2 |
| [0002](0002-wrong-answers-query-based.md) | 오답노트는 Submission 조회 기반으로 시작 | 채택됨 | MVP / 로드맵4 |

## 작성 예정 (후보)
- 캐시 무효화 전략 선택 (로드맵 2)
- 한글 풀텍스트 검색: ngram vs Elasticsearch (검색 레이어)
- 인덱스 설계와 실행계획 근거 (로드맵 1)
- QueryDSL 도입과 N+1 해결 (로드맵 1)
- 레이트 리미팅 방식 (로드맵 3)
- refresh 토큰 저장 위치·회전 전략 (로드맵 2, ADR-0001 후속)
