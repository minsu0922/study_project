# 작업 지침

이 프로젝트는 백엔드 CS 개념을 하나씩 실증하며 배우는 **학습용 포트폴리오**다.
자율 진행·주석·설명 눈높이·커밋 규칙은 전역 CLAUDE.md를 따른다.
여기에는 이 프로젝트에만 해당하는 규칙만 적는다.

## Git

- push와 삭제만 먼저 묻는다. 나머지 git 명령은 바로 실행한다.
- 삭제로 치는 것: `git rm`, `branch -D`, `tag -d`, `reset --hard`, `clean -fd`, `checkout --`로 변경 버리기

## 셸 명령

- 빌드·테스트·조회·앱 실행은 바로 한다. PowerShell도 같다.
- 먼저 묻는 것: 파일·DB 대량 삭제(`Remove-Item -Recurse`, `DROP`), 시스템 설정 변경, 외부 전송

## 문서 위치

- 개선 경위: `docs/IMPROVEMENTS.md`
- 면접 대본: `docs/INTERVIEW_SCRIPT.md` (주석에 넣지 않는다)
- 용어: `docs/GLOSSARY.md`에 정한 말을 쓴다

## 문서 윤문

- `docs/`에 문서를 새로 쓰거나 크게 고치면, 마지막 단계로 `humanize-korean` 스킬을 돌린다.
- 윤문 뒤 코드 블록·수치·파일 경로·용어가 그대로인지 원문과 대조한다.
- 배치가 만드는 개념 문서에는 아직 적용하지 않는다.
