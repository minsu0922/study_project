# GitHub Actions에 SonarCloud 붙이기 (1편: 도입 계기와 연동)

> 2편: 첫 분석 결과 41건을 고친 것과 넘긴 것으로 나누기 (작성 예정)

## 이 글에서 다루는 것

- 혼자 만드는 Spring Boot 프로젝트에 왜 정적 분석 도구를 붙였는지
- SonarCloud를 GitHub Actions에 연동한 순서와, 단계마다 그렇게 정한 이유
- 연동하고 나서 어떤 데이터를 얻을 수 있는지

프로젝트는 백엔드 CS 개념을 퀴즈로 공부하는 서비스입니다. Spring Boot 3.4, Java 21, MySQL, Redis를 쓰고 코드는 약 2만 5천 줄입니다. GitHub Actions로 push마다 빌드·테스트를 돌리고 통과하면 도커 이미지를 GHCR에 올립니다.

## 도입 계기: 짠 사람이 검토하면 같은 곳을 놓친다

이 프로젝트는 AI 코딩 도구와 함께 만들었습니다. 기능이 늘면서 흐름이 꼬였거나 같은 일을 두 번 하는 코드가 생겼는지 점검할 때가 왔습니다.

처음에는 AI에게 코드 리뷰를 맡겼습니다. 그런데 하나가 걸렸습니다. 코드를 짠 AI와 리뷰하는 AI가 같은 모델이면 짤 때 놓친 곳을 리뷰할 때도 똑같이 놓칩니다. 그래서 역할을 나눴습니다.

| 점검 대상 | 맡은 쪽 |
|---|---|
| 기능 흐름(생성 → 검수 → 공개) | AI는 흐름도만 그리고, 판정은 다른 회사 AI와 사람이 한다 |
| 코드 품질(버그 의심, 보안 약점, 중복) | 규칙 기반 정적 분석 도구에 맡긴다 |

테스트만으로는 부족한 이유도 있었습니다. 테스트는 "내가 확인하려고 한 것"이 맞는지만 봅니다. 테스트가 코드의 어디를 지나가지 않는지는 알려 주지 않습니다. 그때까지 커버리지 측정(JaCoCo)도 하지 않은 상태였습니다.

## SonarCloud란

정적 분석은 코드를 실행하지 않고 읽기만 해서 문제를 찾는 검사입니다. 맞춤법 검사기가 글을 읽으며 틀린 곳에 밑줄을 긋듯, 분석기가 규칙 수천 개로 코드를 훑어 의심스러운 곳을 표시합니다.

SonarCloud는 이 분석을 클라우드에서 해 주는 서비스입니다. 2024년에 이름이 SonarQube Cloud로 바뀌었지만 주소와 화면 곳곳에 SonarCloud라는 이름이 남아 있어 이 글에서도 그대로 씁니다.

| 도구 | 어디서 도나 | 특징 |
|---|---|---|
| SonarQube Server | 내가 띄운 서버 | 서버를 직접 운영해야 한다 |
| SonarCloud | Sonar가 운영하는 클라우드 | 공개 저장소는 무료, GitHub 계정으로 바로 연결 |
| SonarLint(IDE 플러그인) | 내 IDE | 작성하는 순간 밑줄. 기록이 남지 않는다 |

저장소가 공개라 비용이 들지 않고, 서버를 운영할 필요도 없어서 SonarCloud를 골랐습니다.

## 연동 방식 고르기: 자동 분석과 CI 분석

SonarCloud는 분석 방식을 두 가지 제공합니다.

| 방식 | 하는 일 | 장단점 |
|---|---|---|
| Automatic Analysis | SonarCloud가 저장소를 직접 읽어 분석 | 설정이 필요 없다. 대신 커버리지를 못 올리고, 자바는 컴파일 결과 없이 읽어 정확도가 떨어진다 |
| CI 분석 | 내 CI에서 빌드한 뒤 결과를 올림 | 설정이 조금 필요하다. 커버리지까지 올릴 수 있다 |

커버리지를 함께 보려고 CI 분석을 골랐습니다. 이미 GitHub Actions에서 MySQL·Redis를 띄워 테스트를 돌리고 있어서 그 job 끝에 단계 하나만 붙이면 됩니다.

## 연동 순서

### 1. SonarCloud에서 프로젝트 가져오기

sonarcloud.io에 GitHub 계정으로 로그인하고 저장소를 가져옵니다. 이때 조직 키와 프로젝트 키가 정해집니다. 제 경우 조직 키는 GitHub 아이디(`minsu0922`), 프로젝트 키는 `조직_저장소` 꼴(`minsu0922_study_project`)이었습니다.

[캡처: SonarCloud 프로젝트 가져오기 화면]

### 2. Automatic Analysis 끄기

프로젝트의 Administration → Analysis Method에서 Automatic Analysis를 끕니다. 켜 둔 채 CI 분석을 올리면 두 방식이 부딪혀 CI 분석 단계가 실패합니다.

[캡처: Analysis Method 화면]

### 3. 토큰을 GitHub Secrets에 넣기

SonarCloud에서 토큰을 발급해 저장소 Settings → Secrets and variables → Actions에 `SONAR_TOKEN`이라는 이름으로 등록합니다.

토큰은 코드나 설정 파일에 적지 않습니다. 공개 저장소라 커밋되는 순간 누구나 볼 수 있습니다. Secrets에 넣은 값은 워크플로가 실행될 때만 환경변수로 들어갑니다.

### 4. build.gradle에 JaCoCo와 Sonar 플러그인 추가

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.4.1'
    id 'io.spring.dependency-management' version '1.1.7'
    id 'jacoco'
    id 'org.sonarqube' version '7.5.0.8588'
}

tasks.named('test') {
    useJUnitPlatform()
    finalizedBy 'jacocoTestReport'
}

// 커버리지 보고서. SonarCloud는 HTML이 아니라 XML만 읽는다.
jacocoTestReport {
    reports {
        xml.required = true
        html.required = true
    }
}

sonar {
    properties {
        property 'sonar.projectKey', 'minsu0922_study_project'
        property 'sonar.organization', 'minsu0922'
        property 'sonar.host.url', 'https://sonarcloud.io'
    }
}
```

- `finalizedBy 'jacocoTestReport'`: 테스트가 끝나면 커버리지 보고서를 바로 만듭니다. 따로 명령을 부르지 않아도 됩니다.
- XML 보고서: SonarCloud는 XML만 읽습니다. HTML은 사람이 로컬에서 볼 때 씁니다.
- 커버리지 보고서 경로는 적지 않았습니다. 같은 프로젝트에 `jacoco` 플러그인이 있으면 Sonar 플러그인이 기본 경로를 알아서 찾습니다.

### 5. 워크플로에 분석 단계 추가

```yaml
steps:
  - name: 저장소 코드 받기
    uses: actions/checkout@...
    with:
      # SonarCloud가 git 이력으로 "새로 바뀐 코드"를 가려낸다.
      fetch-depth: 0

  # (JDK·Gradle 설정 생략)

  - name: 빌드 + 테스트
    run: ./gradlew build --no-daemon

  # 품질 기준(Quality Gate)을 못 넘어도 CI를 막지 않는다.
  - name: SonarCloud 분석
    env:
      SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
    run: ./gradlew sonar --no-daemon
```

여기서 정한 것이 세 가지입니다.

| 결정 | 이유 |
|---|---|
| `fetch-depth: 0` | 기본값은 마지막 커밋 하나만 받는다. SonarCloud는 git 이력으로 어느 줄이 새 코드인지 가리므로 이력 전체가 필요하다 |
| 테스트 job 안에 붙이기 | 방금 만든 JaCoCo 보고서를 그대로 올린다. job을 나누면 테스트를 다시 돌리거나 파일을 넘겨야 한다 |
| 품질 기준을 넘지 못해도 CI를 막지 않기 | 첫 분석에는 기존 코드의 지적이 한꺼번에 쏟아진다. 바로 막으면 그것을 다 정리할 때까지 push가 모두 실패한다 |

마지막 항목은 Sonar 플러그인의 기본값입니다. `sonar.qualitygate.wait`가 `false`라 분석 결과만 올리고 판정을 기다리지 않습니다. 기존 지적을 정리한 뒤 `true`로 바꿔 CI를 막을 생각입니다.

### 6. push하고 확인

push하면 빌드 → 테스트 → 커버리지 보고서 → 분석 순서로 돕니다. 첫 실행은 두 job 모두 통과했고 몇 분 뒤 SonarCloud 대시보드에 결과가 올라왔습니다.

[캡처: GitHub Actions 실행 화면에서 SonarCloud 분석 단계]

## 얻을 수 있는 데이터

### 대시보드 지표

첫 분석 결과입니다.

| 지표 | 뜻 | 첫 분석 값 |
|---|---|---|
| 코드 줄 수 | 빈 줄·주석을 뺀 줄 수 | 24,877 |
| 커버리지 | 테스트가 지나간 코드 비율 | 81.6% |
| 버그 | 실행하면 잘못 동작할 수 있는 코드 | 7 |
| 취약점 | 공격에 쓰일 수 있는 코드 | 11 |
| 코드 냄새 | 당장 틀리진 않지만 고치기 어렵게 만드는 코드 | 23 |
| 보안 핫스팟 | 사람이 직접 보고 안전한지 판단할 곳 | 0 |
| 중복 | 같은 코드가 되풀이된 비율 | 0.4% |

[캡처: SonarCloud 대시보드 첫 화면]

### 지적마다 붙는 정보

지적 하나를 열면 다음이 나옵니다.

- 파일과 줄 위치, 그리고 문제가 되는 코드 조각
- 어긴 규칙(예: `java:S2229`)과 규칙 설명, 고친 예시
- 심각도(Blocker·Critical·Major 등)

이 정보로 지적마다 "고칠 것"과 "이 프로젝트에서는 안전한 것"을 가릴 수 있습니다. 안전하다고 판단한 지적은 False positive나 Accept로 표시하고 이유를 메모로 남깁니다. 그러면 다음 분석부터 그 지적이 다시 올라오지 않습니다.

[캡처: 지적 상세 화면]

### 새 코드와 품질 기준

SonarCloud는 "전체 코드"와 "새 코드"를 나눠 봅니다. 품질 기준(Quality Gate)은 기본으로 새 코드에만 적용됩니다. 예전 코드의 지적이 수백 건이어도 이번에 바꾼 코드가 깨끗하면 통과합니다.

그래서 첫 분석의 품질 기준 판정은 `NONE`이었습니다. 비교할 기준점이 아직 없어서입니다. 버그·취약점 일부를 고쳐 다시 push하자 `OK` 판정이 붙었습니다.

### API로 꺼내기

공개 프로젝트는 토큰 없이 API로 지표를 꺼낼 수 있습니다. README 배지나 블로그 수치를 갱신할 때 씁니다.

```bash
curl "https://sonarcloud.io/api/measures/component?component=minsu0922_study_project&metricKeys=coverage,bugs,vulnerabilities,code_smells"
```

## 마치며

연동 자체는 파일 두 개를 고치고 웹에서 설정 세 가지를 바꾸면 끝납니다. 시간이 드는 부분은 그 뒤에 있습니다.

첫 분석에서 버그·취약점 18건, 코드 냄새 23건이 나왔습니다. 41건을 하나씩 열어 보니 실제로 고칠 것과, 도구가 앞뒤 맥락을 못 봐서 잘못 짚은 것이 섞여 있었습니다. 2편에서는 41건을 어떤 기준으로 나눴는지, 그리고 그 과정에서 찾은 실제 버그를 다룹니다.
