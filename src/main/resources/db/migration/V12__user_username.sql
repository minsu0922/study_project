-- =====================================================================
-- V12__user_username.sql — 로그인 아이디를 이메일에서 username으로 (2026-08-19)
-- =====================================================================
-- [왜 바꾸나]
-- 로그인할 때마다 "minsu092274@gmail.com"을 치는 것이 번거롭다는 사용자 요구.
-- 이 서비스는 메일을 보내지 않는다(인증 메일도, 비밀번호 찾기도 없다). 그러니
-- 이메일을 받는 것은 <쓰지 않을 정보를 매번 입력하게 하는 것>이었다.
--
-- [email을 지우지 않는 이유]
-- 언젠가 비밀번호 찾기나 알림을 붙이면 그때 필요해진다. 컬럼을 지우는 것은
-- 되돌릴 수 없고(기존 주소가 사라진다) 얻는 것은 컬럼 하나뿐이라, NOT NULL만
-- 풀어 <선택 항목>으로 남긴다. 지금은 어떤 코드도 이 값을 읽지 않는다.
--
-- [기존 계정은 어떻게 되나]
-- email의 @ 앞부분을 username으로 삼는다(admin@csquiz.local → admin).
-- 겹치는 값이 생기면 UNIQUE 제약에 걸려 이 마이그레이션이 실패한다 — 그게 맞다.
-- 조용히 뒤 번호를 붙이면 "내 아이디가 minsu2가 된" 것을 아무도 모른 채 지나간다.
-- =====================================================================

-- 1) 컬럼 추가. 값을 채우기 전이라 일단 NULL 허용으로 만든다.
--    30자인 이유: 아이디 규칙이 4~20자라 여유를 두되, 이메일(255)만큼 길 이유는 없다.
ALTER TABLE user ADD COLUMN username VARCHAR(30) NULL AFTER id;

-- 2) 기존 행 채우기 — @ 앞부분을 소문자로.
--    SUBSTRING_INDEX(s, '@', 1): 첫 '@' 앞까지 잘라 낸다. '@'가 없으면 문자열 전체.
--    LOWER를 거는 이유는 아래 3)의 주석 참고.
UPDATE user SET username = LOWER(SUBSTRING_INDEX(email, '@', 1)) WHERE username IS NULL;

-- 3) 이제 필수 + 유일. 로그인 아이디이므로 둘 다 없으면 안 된다.
--    이 테이블의 collation은 utf8mb4_0900_ai_ci(대소문자 구분 안 함)라 DB만으로도
--    'Minsu'와 'minsu'가 같은 값으로 취급되지만, 애플리케이션에서도 소문자로 낮춰
--    저장한다(AuthService). DB 설정에 기대면 collation이 바뀌는 날 조용히 깨진다.
ALTER TABLE user MODIFY COLUMN username VARCHAR(30) NOT NULL;
ALTER TABLE user ADD CONSTRAINT uk_user_username UNIQUE (username);

-- 4) email은 선택 항목으로. UNIQUE는 그대로 둔다 — 나중에 값을 받게 되면
--    "한 주소로 여러 계정"은 여전히 막아야 하고, MySQL의 UNIQUE는 NULL을 여럿 허용한다.
ALTER TABLE user MODIFY COLUMN email VARCHAR(255) NULL;
