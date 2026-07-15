---
name: verify
description: csquiz(Spring Boot + 정적 프론트) 변경을 실제로 띄워 확인하는 레시피 — 빌드/기동/API 호출/브라우저 확인
---

# csquiz 검증 레시피

## 전제
- MySQL: docker-compose로 localhost:3306/csquiz 가동 중이어야 함(테스트·앱 공용).
- Redis(WSL): 꺼져 있어도 됨 — 로그인은 되고 refreshToken만 null(fail-open 설계).

## 기동
```bash
./gradlew bootRun   # run_in_background로. 포트 8080, 기동 ~5초
# 준비 확인: curl -s -o /dev/null http://localhost:8080/ 이 200이면 UP
```
종료는 PowerShell: `Get-NetTCPConnection -LocalPort 8080 -State Listen` → OwningProcess를 Stop-Process.
(이 방식으로 죽이면 백그라운드 gradle 태스크가 exit 1로 "실패" 알림을 내는데 정상이다.)

## API로 인증 흐름 만들기
```bash
curl -s -X POST localhost:8080/api/auth/signup -H "Content-Type: application/json" \
  -d '{"email":"verify-<유니크>@test.local","password":"Passw0rd!23"}'
curl -s -X POST localhost:8080/api/auth/login ... # data.accessToken 추출
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/me/...
```
- 응답은 공통 봉투 {success,data,error}. 보호 경로는 /api/me/**(401 확인용으로도 씀).
- 한글 JSON을 PowerShell ConvertFrom-Json으로 파싱하면 콘솔 인코딩 때문에 깨진다 —
  grep -o로 필요한 조각만 뽑는 게 안전.

## 브라우저(UI) 확인
- Claude in Chrome으로 localhost:8080 접근 가능. 로그인: login.html에서 form_input →
  로그인 버튼은 ref 클릭이 안 먹을 수 있어 좌표 클릭이 확실했다.
- 브라우저 localStorage에 이전 세션 로그인이 남아 있을 수 있음(만료 토큰이면
  첫 API 호출에서 자동 로그아웃됨) — 테스트 계정으로 다시 로그인하고 시작할 것.

## 자주 확인하는 흐름
- 퀴즈/데일리 풀이: 홈(/) 카드 → daily.html 또는 quiz.html → 플레이어에서 보기 클릭 → 제출.
- 제출 반영은 서버 한 트랜잭션(채점+사다리+데일리 세트) — 화면 갱신은 재조회로 확인.
