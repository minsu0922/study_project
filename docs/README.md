# CS 지식베이스 & 문제풀이 플랫폼 — 설계 문서

CS 학습 플랫폼(도메인)을 만들며 백엔드 CS 개념을 하나씩 실증하는 포트폴리오.
각 기술 결정은 ADR로 남겨 **학습 문서이자 면접 대본**으로 쓴다.

## 문서 맵
| 문서 | 내용 |
|---|---|
| [01-data-model](01-data-model.md) | 테이블·컬럼·인덱스·채점 로직·참조 무결성 |
| [02-domain-enums](02-domain-enums.md) | Domain(10)/Difficulty(3)/ProblemType(4) enum |
| [03-api-spec](03-api-spec.md) | MVP 7개 엔드포인트 요청/응답 상세 |
| [04-response-format](04-response-format.md) | 공통 응답 envelope + 에러 코드 + 전역 예외처리 |
| [05-package-structure](05-package-structure.md) | 도메인별 수직 분할 패키지 구조 |
| [adr/](adr/README.md) | Architecture Decision Records |

## 기술 스택 (확정)
Java 21 · Spring Boot 3.4.1 · MySQL 8(InnoDB) · Spring Data JPA(+QueryDSL 예정) ·
Spring Security + JWT · Redis(예정) · springdoc-openapi · Docker Compose · JUnit5 + Testcontainers · Flyway

## MVP 범위 (확정)
- **인증**: access 토큰만 (refresh는 로드맵 2) — [ADR-0001](adr/0001-access-token-only-for-mvp.md)
- **채점**: 객관식/OX/단답형 3종 자동채점, 서술형 제외
- **오답노트**: Submission 조회 기반 — [ADR-0002](adr/0002-wrong-answers-query-based.md)
- **공통 응답 envelope + 전역 예외처리** (첫 API 전 구축)
- **Flyway** (`ddl-auto=validate`)
- **목록 API는 Pageable** (기본 size 20)

## 레이어 로드맵 (붙이는 순서 = 공부 순서)
1. 인덱스 + QueryDSL (N+1 해결, 인덱스 효과 측정)
2. Redis 캐싱 (무효화·TTL·스탬피드) + refresh 토큰
3. 레이트 리미팅 + 표준 에러 응답 확장
4. 복습 추천 (망각곡선) → `ReviewItem` 분리
5. CI/CD + 배포 (GitHub Actions)

## 첫 마일스톤
docker-compose로 MySQL 기동 → Spring Boot 초기화 → User 엔티티 + 회원가입 API.

## 구현 순서 (제안)
1. `build.gradle` 정리 (MVP 의존성 + Flyway)
2. `docker-compose.yml`(MySQL 8) + `application.yml`
3. `global`: 공통 응답 envelope + 전역 예외처리
4. `User` 엔티티 + Flyway `V1__init.sql`
5. 회원가입/로그인 API + Security/JWT
