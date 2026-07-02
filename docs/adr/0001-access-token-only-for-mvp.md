# ADR-0001: MVP는 access 토큰만, refresh는 Redis 레이어로 유예

- 상태: 채택됨
- 날짜: 2026-07-01
- 관련 로드맵: MVP(인증) / 로드맵 2(Redis 캐싱)

## 맥락 (Context)
인증은 JWT로 확정. 스펙엔 access/refresh 이중 토큰이 명시돼 있으나, MVP 단계엔 Redis가 아직 없다.
refresh 토큰을 "제대로" 운용하려면 무효화(로그아웃/탈취 대응)를 위한 서버측 저장소가 필요하다.

## 검토한 선택지 (Options)
1. **access만 발급** — 구현 단순. 무효화 불필요(짧은 만료로 커버). 단, 자동 재로그인 UX 없음.
2. **access + refresh(DB 테이블 저장)** — 무효화 가능. 단, 곧 Redis 도입 예정이라 저장소가 이사(migration) 대상이 되고, "왜 세션성 데이터를 RDB에?"라는 어정쩡함.
3. **access + refresh(클라이언트만 보관)** — 서버 상태 없음. 단, 탈취/로그아웃 무효화 불가 → 보안 도메인 실증 목적과 상충.

## 결정 (Decision)
**1안(access만)** 채택. access 만료 1시간. refresh + 무효화는 로드맵 2에서 Redis와 함께 도입.
근거: (a) MVP 복잡도 최소화, (b) refresh 저장소는 본질적으로 세션성 → RDB보다 Redis가 정석, (c) "access만 → 재로그인/무효화 필요성 체감 → Redis+refresh 도입" 순서가 학습·면접 서사로 자연스럽다.

## 결과 (Consequences)
- 긍정적: 인증 구현이 가벼워 MVP 속도↑. 이후 refresh 도입이 "개선 스토리"가 됨.
- 트레이드오프: MVP 동안 토큰 만료 시 재로그인 필요. 강제 로그아웃 불가.
- 재검토 트리거: 로드맵 2(Redis) 진입 시점. 이때 refresh 저장 위치·회전(rotation)·재사용 감지 ADR을 후속 작성.

## 면접 대본 요약
"처음부터 refresh를 넣지 않았습니다. refresh의 핵심 가치는 서버측 무효화인데, 그러려면 세션성 저장소가 필요합니다. MVP엔 Redis가 없어서 RDB에 억지로 넣기보다, access 단일 토큰으로 시작하고 캐싱 레이어에서 Redis를 도입할 때 refresh와 토큰 회전을 함께 붙였습니다. 필요를 먼저 증명하고 도구를 투입한 사례입니다."
