# 02. 도메인 Enum 정의

> 원칙: **Java enum 상수는 영문, 화면 표기(displayName)는 한글.** DB에는 enum 이름(영문)을 `VARCHAR`로 저장(`@Enumerated(EnumType.STRING)`).
> `EnumType.ORDINAL` 금지 — 순서 바뀌면 데이터 깨짐(ADR 없이도 지키는 규칙).

---

## Domain (10개)

| enum 상수 | displayName | 비고 |
|---|---|---|
| `NETWORK` | 네트워크 | |
| `OS` | 운영체제 | |
| `DATABASE` | 데이터베이스 | |
| `DS_ALGORITHM` | 자료구조·알고리즘 | |
| `SYSTEM_DESIGN` | 시스템설계 | |
| `SECURITY` | 보안 | |
| `LANGUAGE_RUNTIME` | 언어·런타임 | JVM/GC 등 |
| `CLOUD_INFRA` | 클라우드·인프라 | |
| `FRONTEND_CS` | 프론트엔드CS | 브라우저·렌더링 등 |
| `INTEGRATED` | 통합시나리오 | 여러 도메인 결합 문제 |

```java
public enum Domain {
    NETWORK("네트워크"),
    OS("운영체제"),
    DATABASE("데이터베이스"),
    DS_ALGORITHM("자료구조·알고리즘"),
    SYSTEM_DESIGN("시스템설계"),
    SECURITY("보안"),
    LANGUAGE_RUNTIME("언어·런타임"),
    CLOUD_INFRA("클라우드·인프라"),
    FRONTEND_CS("프론트엔드CS"),
    INTEGRATED("통합시나리오");

    private final String displayName;
    Domain(String displayName) { this.displayName = displayName; }
    public String getDisplayName() { return displayName; }
}
```

---

## Difficulty (3개)

| enum 상수 | displayName |
|---|---|
| `BEGINNER` | 초급 |
| `INTERMEDIATE` | 중급 |
| `ADVANCED` | 고급 |

---

## ProblemType (4개, MVP는 3개 채점)

| enum 상수 | displayName | MVP 자동채점 |
|---|---|---|
| `MULTIPLE_CHOICE` | 객관식 | ✅ |
| `OX` | OX | ✅ |
| `SHORT_ANSWER` | 단답형 | ✅ |
| `ESSAY` | 서술형 | ❌ (enum 유지, 채점/시드 제외) |

---

## API 요청에서의 표기

- 쿼리 파라미터·JSON 필드는 **영문 enum 상수명**을 그대로 사용 (예 `?domain=NETWORK&level=BEGINNER`).
- 화면 노출용 한글이 필요하면 응답 DTO에 `domainLabel` 등으로 displayName을 함께 내려준다.
- 잘못된 enum 값 → `400 VALIDATION_ERROR` (전역 예외처리, `04-response-format` 참고).
