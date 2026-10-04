# 문제별 토론 (커뮤니티 1단계) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 문제마다 토론방을 하나 두고, 학습자가 해설 아래에서 댓글·답글로 이야기하며, 관리자가 신고된 글을 가릴 수 있게 한다.

**Architecture:** 새 패키지 `discussion`에 토론방(`discussion`)·댓글(`comment`)·신고(`comment_report`) 세 표를 둔다. 댓글은 문제가 아니라 토론방에 묶여, 뒤 단계의 게시판이 같은 댓글·신고 코드를 쓴다. 읽기는 기존 공개 경로 `/api/quiz/**`에, 쓰기는 기존 보호 경로 `/api/me/**`에 얹어 `SecurityConfig`를 건드리지 않는다. 화면은 `js/discussion.js` 한 파일이 그리고 플레이어·오답노트가 같이 쓴다.

**Tech Stack:** Spring Boot 3.4.1, Java 21, Spring Data JPA, MySQL 8, Flyway, Redis(요청 제한), 정적 HTML/JS, JUnit 5 + MockMvc.

**Spec:** `docs/superpowers/specs/2026-10-03-problem-discussion-design.md`

## Global Constraints

- 마이그레이션은 `V21__problem_discussion.sql` 한 파일이다. 스키마만 넣고 데이터는 넣지 않는다.
- 본문은 평문 1,000자까지. 닉네임은 2~12자, 한글·영문·숫자·밑줄만 받는다.
- 목록은 시간순, 20개씩. 답글은 한 단계까지만 받는다.
- 댓글 작성 도배 방지는 사용자당 분당 5건이다.
- 글쓴이 id는 토큰에서만 꺼낸다(`@AuthenticationPrincipal Long userId`). 요청 본문으로 받지 않는다.
- 권한은 경로가 가른다. 읽기는 `GET /api/quiz/**`(공개), 쓰기는 `/api/me/**`(로그인), 운영은 `/api/admin/**`(관리자). `SecurityConfig`에 규칙을 더하지 않는다.
- 응답은 기존 봉투(`ApiResponse.ok(...)`)를 쓴다. 오류는 `BusinessException(ErrorCode.X)`로 던진다.
- 생성자 주입(`@RequiredArgsConstructor`)을 쓴다. 엔티티를 응답으로 내보내지 않는다.
- 주석은 코드만 봐선 이유를 모를 곳에만 단다. 인라인 2줄, 메서드·상수 설명 5줄까지.
- 통합 테스트는 로컬 MySQL(`localhost:3306/csquiz`)이 필요하다. `@Transactional`로 롤백한다.
- 테스트 실행은 PowerShell에서 `.\gradlew.bat test --tests "*클래스이름" --console=plain`이다.
- 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`을 붙인다. PowerShell에서는 메시지를 파일로 써서 `git commit -F <파일>`로 넘긴다(본문의 큰따옴표가 인자를 깨뜨린다).
- 작업 브랜치는 `feat/problem-discussion`이다. push는 사용자에게 먼저 묻는다.

## Review Focus

설계 문서가 말하지 않았지만 실제로 들어올 입력들이다. 각 줄의 테스트는 표시한 작업에 넣었다.

1. **댓글 입력 중 플레이어 단축키** — 플레이어는 `document`의 `keydown`으로 숫자 키와 Enter를 받는다. 댓글 칸에서 Enter를 치면 다음 문제로 넘어간다. 토론 영역 안의 키 입력은 플레이어가 무시해야 한다. (Task 6, 브라우저 확인)
2. **다른 문제의 댓글을 `parentId`로 준 답글** — 문제 A의 토론에 문제 B의 댓글을 부모로 지정하면 404여야 한다. 받아 주면 한 방의 답글이 다른 방 원글에 매달린다. (Task 3)
3. **대소문자만 다른 닉네임** — `Minsu`와 `minsu`는 같은 이름으로 막아야 한다. 화면에서 두 사람이 구분되지 않는다. (Task 2)
4. **탈퇴한 사람의 댓글** — 계정이 지워져도 댓글과 그 아래 답글은 남고, 글쓴이는 비어 나가야 한다. 탈퇴가 외래키에 걸려 실패하면 안 된다. (Task 3)
5. **본문에 HTML을 넣은 댓글** — `<img src=x onerror=alert(1)>`이 글자 그대로 보여야 한다. (Task 6, 브라우저 확인)

---

## File Structure

| 파일 | 책임 |
|---|---|
| `src/main/resources/db/migration/V21__problem_discussion.sql` (신규) | 세 표와 `user.nickname` |
| `discussion/domain/Discussion.java` (신규) | 토론방 엔티티. 읽기 전용 |
| `discussion/domain/Comment.java`, `CommentStatus.java` (신규) | 댓글과 상태 전이 |
| `discussion/domain/CommentReport.java`, `CommentReportReason.java` (신규) | 신고와 사유 |
| `discussion/repository/DiscussionRepository.java` (신규) | 방 만들기(중복 무시)와 조회 |
| `discussion/repository/CommentRepository.java` (신규) | 묶음·답글·개수 조회 |
| `discussion/repository/CommentReportRepository.java` (신규) | 신고 목록·개수·일괄 인정 |
| `discussion/dto/*.java` (신규) | 요청·응답 record |
| `discussion/service/CommentService.java` (신규) | 읽기·쓰기·수정·삭제와 권한 |
| `discussion/service/CommentReportService.java` (신규) | 신고 접수와 관리자 판정 |
| `discussion/controller/CommentController.java` (신규) | 공개 읽기 API |
| `discussion/controller/MyCommentController.java` (신규) | 로그인 쓰기 API |
| `admin/controller/AdminCommentController.java` (신규) | 관리자 API |
| `user/domain/User.java`, `user/repository/UserRepository.java` (변경) | 닉네임 |
| `user/service/AccountService.java`, `user/controller/AccountController.java` (변경) | 닉네임 API |
| `quiz/repository/SubmissionRepository.java` (변경) | "풀었는지" 조회 |
| `global/exception/ErrorCode.java` (변경) | `DISCUSSION_001`~`009` |
| `global/ratelimit/RateLimitProperties.java`, `RateLimitFilter.java` (변경) | 댓글 작성 정책 |
| `src/main/resources/application.yml` (변경) | `ratelimit.comment` |
| `static/js/discussion.js` (신규) | 토론 영역 그리기와 동작 |
| `static/css/style.css` (변경) | 토론 영역 모양 |
| `static/js/player.js`, `quiz.html`, `daily.html`, `review.html`, `wrong-answers.html`, `problems.html`, `mypage.html` (변경) | 토론 붙이기, 댓글 수, 닉네임 칸 |
| `static/admin/comments.html` (신규), `static/admin/js/admin-shell.js` (변경) | 신고함과 메뉴 배지 |
| `docs/04-response-format.md` (변경) | 오류 코드 표 |

위 경로에서 `discussion/`, `user/`, `quiz/`, `global/`, `admin/`은 `src/main/java/project/study/study_project/` 아래이고, `static/`은 `src/main/resources/static/`이다. 테스트는 `src/test/java/project/study/study_project/discussion/` 아래에 둔다.

---

### Task 1: 스키마, 엔티티, 저장소, 오류 코드

**Files:**
- Create: `src/main/resources/db/migration/V21__problem_discussion.sql`
- Create: `src/main/java/project/study/study_project/discussion/domain/Discussion.java`
- Create: `src/main/java/project/study/study_project/discussion/domain/CommentStatus.java`
- Create: `src/main/java/project/study/study_project/discussion/domain/Comment.java`
- Create: `src/main/java/project/study/study_project/discussion/repository/DiscussionRepository.java`
- Create: `src/main/java/project/study/study_project/discussion/repository/CommentRepository.java`
- Modify: `src/main/java/project/study/study_project/user/domain/User.java`
- Modify: `src/main/java/project/study/study_project/user/repository/UserRepository.java`
- Modify: `src/main/java/project/study/study_project/global/exception/ErrorCode.java`
- Modify: `docs/04-response-format.md`
- Test: `src/test/java/project/study/study_project/discussion/DiscussionSchemaIntegrationTest.java`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `DiscussionRepository.insertIfAbsent(Long problemId)`, `Optional<Long> findIdByProblemIdForShare(Long problemId)`, `Optional<Long> findIdByProblemId(Long problemId)`, `long countByProblemId(Long problemId)`
  - `Comment.of(Long discussionId, Long userId, Long parentId, String body)`, `edit(String)`, `delete()`, `hide()`, `restore()`, `isVisible()`
  - `CommentRepository.findThreads(Long discussionId, CommentStatus deleted, Pageable)`, `findReplies(Collection<Long> parentIds, CommentStatus deleted)`, `countByDiscussionIdAndStatus(Long, CommentStatus)`, `countByProblemIds(Collection<Long>, CommentStatus visible)` → `List<ProblemCommentCount>`(`getProblemId()`, `getCnt()`)
  - `User.getNickname()`, `User.changeNickname(String)`, `UserRepository.existsByNicknameAndIdNot(String, Long)`
  - `ErrorCode.DISCUSSION_001` ~ `DISCUSSION_009`

- [ ] **Step 1: 브랜치를 만든다**

```powershell
git switch -c feat/problem-discussion
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`src/test/java/project/study/study_project/discussion/DiscussionSchemaIntegrationTest.java`

```java
package project.study.study_project.discussion;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.discussion.domain.Comment;
import project.study.study_project.discussion.domain.CommentStatus;
import project.study.study_project.discussion.repository.CommentRepository;
import project.study.study_project.discussion.repository.DiscussionRepository;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;

import static org.assertj.core.api.Assertions.assertThat;

/** V21이 약속한 제약을 DB에서 직접 밟아 본다 — 방은 문제당 하나, 문제가 지워지면 함께 지워진다. */
@SpringBootTest
@Transactional
class DiscussionSchemaIntegrationTest {

    @Autowired
    private DiscussionRepository discussionRepository;
    @Autowired
    private CommentRepository commentRepository;
    @Autowired
    private ProblemRepository problemRepository;
    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("같은 문제에 방을 두 번 만들어도 하나만 생긴다")
    void roomIsCreatedOncePerProblem() {
        Long problemId = saveProblem().getId();

        discussionRepository.insertIfAbsent(problemId);
        discussionRepository.insertIfAbsent(problemId);

        assertThat(discussionRepository.countByProblemId(problemId)).isEqualTo(1);
        assertThat(discussionRepository.findIdByProblemIdForShare(problemId)).isPresent();
    }

    @Test
    @DisplayName("방이 없는 문제는 빈 값을 돌려준다")
    void noRoomYet() {
        assertThat(discussionRepository.findIdByProblemId(saveProblem().getId())).isEmpty();
    }

    @Test
    @DisplayName("문제를 지우면 방과 댓글도 함께 지워진다")
    void deletingProblemRemovesDiscussion() {
        Problem problem = saveProblem();
        discussionRepository.insertIfAbsent(problem.getId());
        Long discussionId = discussionRepository.findIdByProblemIdForShare(problem.getId()).orElseThrow();
        Long commentId = commentRepository.saveAndFlush(Comment.of(discussionId, null, null, "첫 글")).getId();

        problemRepository.delete(problem);
        em.flush();
        em.clear();

        assertThat(discussionRepository.countByProblemId(problem.getId())).isZero();
        assertThat(commentRepository.findById(commentId)).isEmpty();
    }

    @Test
    @DisplayName("댓글은 VISIBLE로 태어나고, 삭제해도 행은 남는다")
    void commentLifecycle() {
        Long problemId = saveProblem().getId();
        discussionRepository.insertIfAbsent(problemId);
        Long discussionId = discussionRepository.findIdByProblemIdForShare(problemId).orElseThrow();
        Comment comment = commentRepository.saveAndFlush(Comment.of(discussionId, null, null, "첫 글"));

        assertThat(comment.getStatus()).isEqualTo(CommentStatus.VISIBLE);
        comment.delete();
        em.flush();
        em.clear();

        assertThat(commentRepository.findById(comment.getId()).orElseThrow().getStatus())
                .isEqualTo(CommentStatus.DELETED);
        assertThat(commentRepository.countByDiscussionIdAndStatus(discussionId, CommentStatus.VISIBLE)).isZero();
    }

    private Problem saveProblem() {
        return problemRepository.save(Problem.create(
                TestDomains.NETWORK, Difficulty.BEGINNER, ProblemType.OX,
                "TCP 3-way handshake",
                "TCP 연결은 3번의 패킷 교환으로 시작한다.", "O", "SYN → SYN+ACK → ACK", null));
    }
}
```

- [ ] **Step 3: 실패를 확인한다**

Run: `.\gradlew.bat test --tests "*DiscussionSchemaIntegrationTest" --console=plain`
Expected: 컴파일 실패 — `package project.study.study_project.discussion.domain does not exist`

- [ ] **Step 4: 마이그레이션을 쓴다**

`src/main/resources/db/migration/V21__problem_discussion.sql`

```sql
-- =====================================================================
-- V21__problem_discussion.sql — 문제별 토론(커뮤니티 1단계)
-- =====================================================================
-- 설계: docs/superpowers/specs/2026-10-03-problem-discussion-design.md
--
-- 댓글은 문제가 아니라 <토론방>에 묶인다. 뒤 단계에서 게시판이 생기면 discussion에
-- post_id를 더해 "게시글마다 방 하나"를 만들 뿐이고, comment와 comment_report는 그대로 쓴다.
-- =====================================================================

-- 글쓴이 표시 이름. 로그인 아이디를 그대로 보여 주면 남이 그 아이디로 로그인을 시도할 수 있다.
-- NULL 허용: 첫 댓글을 쓸 때 정한다. 기본 콜레이션(ai_ci)이라 대소문자만 다른 이름도 겹친다.
ALTER TABLE `user`
    ADD COLUMN nickname VARCHAR(12) NULL AFTER username,
    ADD CONSTRAINT uk_user_nickname UNIQUE (nickname);

CREATE TABLE discussion (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    -- NULL 허용: 3단계의 게시글 방을 위한 자리다. 유일 제약은 NULL을 여럿 허용한다.
    problem_id BIGINT      NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_discussion_problem (problem_id),
    -- CASCADE: 문제가 사라지면 토론이 가리킬 대상이 없다(V17의 제보와 같은 판단).
    CONSTRAINT fk_discussion_problem FOREIGN KEY (problem_id) REFERENCES problem (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE comment (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    discussion_id BIGINT        NOT NULL,
    user_id       BIGINT        NULL,                 -- 탈퇴하면 NULL. 토론의 흐름은 남긴다
    parent_id     BIGINT        NULL,                 -- NULL이면 댓글, 값이 있으면 답글(한 단계까지만)
    body          VARCHAR(1000) NOT NULL,
    status        VARCHAR(10)   NOT NULL,             -- enum CommentStatus: VISIBLE / HIDDEN / DELETED
    created_at    DATETIME(6)   NOT NULL,
    edited_at     DATETIME(6)   NULL,
    PRIMARY KEY (id),
    -- 한 방의 댓글을 시간순으로 쪽 나눠 읽는다: 등치(discussion_id, parent_id) → 정렬(created_at).
    KEY idx_comment_discussion_parent_created (discussion_id, parent_id, created_at),
    CONSTRAINT fk_comment_discussion FOREIGN KEY (discussion_id) REFERENCES discussion (id) ON DELETE CASCADE,
    CONSTRAINT fk_comment_user       FOREIGN KEY (user_id)       REFERENCES `user` (id)     ON DELETE SET NULL,
    CONSTRAINT fk_comment_parent     FOREIGN KEY (parent_id)     REFERENCES comment (id)    ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE comment_report (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    comment_id  BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    reason      VARCHAR(30)  NOT NULL,               -- enum CommentReportReason
    detail      VARCHAR(500) NULL,
    status      VARCHAR(15)  NOT NULL,               -- enum ReportStatus: PENDING / ACCEPTED / DISMISSED
    admin_note  VARCHAR(500) NULL,
    created_at  DATETIME(6)  NOT NULL,
    resolved_at DATETIME(6)  NULL,
    PRIMARY KEY (id),
    -- 한 사람이 같은 글을 한 번만 신고한다. 동시 클릭도 DB가 막는다(V17과 같은 패턴).
    UNIQUE KEY uk_comment_report (comment_id, user_id),
    KEY idx_comment_report_status_created (status, created_at),
    CONSTRAINT fk_comment_report_comment FOREIGN KEY (comment_id) REFERENCES comment (id) ON DELETE CASCADE,
    CONSTRAINT fk_comment_report_user    FOREIGN KEY (user_id)    REFERENCES `user` (id)  ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
```

- [ ] **Step 5: 엔티티를 쓴다**

`src/main/java/project/study/study_project/discussion/domain/CommentStatus.java`

```java
package project.study.study_project.discussion.domain;

/** 댓글 상태. 행은 지우지 않고 상태만 바꾼다 — 답글이 달린 글의 자리가 남아야 한다. */
public enum CommentStatus {
    VISIBLE,
    /** 관리자가 가렸다. 복구할 수 있다. */
    HIDDEN,
    /** 글쓴이가 지웠다. 되돌리지 않는다. */
    DELETED
}
```

`src/main/java/project/study/study_project/discussion/domain/Discussion.java`

```java
package project.study.study_project.discussion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 토론방 — DB의 {@code discussion} 테이블(V21). 문제 하나에 방 하나다.
 *
 * <p>생성자를 열지 않는다. 방은 {@code DiscussionRepository.insertIfAbsent}로만 만든다 —
 * 첫 댓글 둘이 동시에 올 때 유일 제약 위반 없이 하나만 생기게 하려면 SQL 한 문장이어야 한다.
 */
@Entity
@Table(name = "discussion")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Discussion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "problem_id")
    private Long problemId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
```

`src/main/java/project/study/study_project/discussion/domain/Comment.java`

```java
package project.study.study_project.discussion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 댓글과 답글 — DB의 {@code comment} 테이블(V21).
 *
 * <p>방·글쓴이·부모를 연관관계가 아니라 id 칸으로 둔다. 목록은 방 id로 한 번, 답글은 부모 id 묶음으로
 * 한 번 읽으므로 객체 탐색이 필요 없고, {@code userId}는 탈퇴하면 NULL이 된다(ProblemReport와 같은 방식).
 */
@Entity
@Table(name = "comment")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "discussion_id", nullable = false)
    private Long discussionId;

    /** 글쓴이 id. 탈퇴한 사용자의 글은 {@code null}이다(외래키 SET NULL). */
    @Column(name = "user_id")
    private Long userId;

    /** {@code null}이면 댓글, 값이 있으면 그 댓글의 답글이다. 답글의 답글은 없다. */
    @Column(name = "parent_id")
    private Long parentId;

    @Column(nullable = false, length = 1000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CommentStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 마지막으로 고친 시각. 한 번도 안 고쳤으면 {@code null} — 화면의 "수정됨" 표시가 이 값을 본다. */
    @Column(name = "edited_at")
    private LocalDateTime editedAt;

    private Comment(Long discussionId, Long userId, Long parentId, String body) {
        this.discussionId = discussionId;
        this.userId = userId;
        this.parentId = parentId;
        this.body = body;
        this.status = CommentStatus.VISIBLE;
    }

    public static Comment of(Long discussionId, Long userId, Long parentId, String body) {
        return new Comment(discussionId, userId, parentId, body);
    }

    public void edit(String body) {
        this.body = body;
        this.editedAt = LocalDateTime.now();
    }

    public void delete() {
        this.status = CommentStatus.DELETED;
    }

    public void hide() {
        this.status = CommentStatus.HIDDEN;
    }

    public void restore() {
        this.status = CommentStatus.VISIBLE;
    }

    public boolean isVisible() {
        return status == CommentStatus.VISIBLE;
    }
}
```

- [ ] **Step 6: 저장소를 쓴다**

`src/main/java/project/study/study_project/discussion/repository/DiscussionRepository.java`

```java
package project.study.study_project.discussion.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.study_project.discussion.domain.Discussion;

import java.util.Optional;

public interface DiscussionRepository extends JpaRepository<Discussion, Long> {

    /**
     * 그 문제의 방이 없으면 만든다. 있으면 아무 일도 하지 않는다.
     *
     * <p>"조회 → 없으면 저장"으로 쓰면 첫 댓글 둘이 동시에 올 때 한쪽이 유일 제약에 걸려 500이 난다.
     * {@code INSERT IGNORE}는 그 충돌을 DB 안에서 끝낸다.
     */
    @Modifying
    @Query(value = "insert ignore into discussion (problem_id, created_at) values (:problemId, now(6))",
            nativeQuery = true)
    void insertIfAbsent(@Param("problemId") Long problemId);

    /**
     * 방 id를 <b>잠금 읽기</b>로 가져온다 — {@link #insertIfAbsent} 바로 뒤에 쓴다.
     *
     * <p>일반 조회는 트랜잭션이 처음 읽은 시점의 스냅샷을 본다(REPEATABLE READ). 다른 트랜잭션이
     * 방금 커밋한 방이 안 보여 "만들었는데 없다"가 된다. {@code FOR SHARE}는 최신 커밋을 읽는다.
     */
    @Query(value = "select id from discussion where problem_id = :problemId for share", nativeQuery = true)
    Optional<Long> findIdByProblemIdForShare(@Param("problemId") Long problemId);

    @Query("select d.id from Discussion d where d.problemId = :problemId")
    Optional<Long> findIdByProblemId(@Param("problemId") Long problemId);

    long countByProblemId(Long problemId);
}
```

`src/main/java/project/study/study_project/discussion/repository/CommentRepository.java`

```java
package project.study.study_project.discussion.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.study_project.discussion.domain.Comment;
import project.study.study_project.discussion.domain.CommentStatus;

import java.util.Collection;
import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    /**
     * 한 방의 댓글(답글 제외)을 시간순으로 읽는다.
     *
     * <p>지워진 댓글은 살아 있는 답글이 있을 때만 남긴다. 화면에서 걸러 내면 20개를 읽었는데
     * 몇 개만 보이는 쪽이 생기므로 조회에서 뺀다.
     */
    @Query(value = """
            select c from Comment c
            where c.discussionId = :discussionId and c.parentId is null
              and (c.status <> :deleted
                   or exists (select 1 from Comment r where r.parentId = c.id and r.status <> :deleted))
            order by c.createdAt asc, c.id asc
            """,
            countQuery = """
            select count(c) from Comment c
            where c.discussionId = :discussionId and c.parentId is null
              and (c.status <> :deleted
                   or exists (select 1 from Comment r where r.parentId = c.id and r.status <> :deleted))
            """)
    Page<Comment> findThreads(@Param("discussionId") Long discussionId,
                              @Param("deleted") CommentStatus deleted,
                              Pageable pageable);

    /** 댓글 묶음의 답글을 한 번에 읽는다 — 댓글마다 따로 읽으면 한 화면에 조회가 20번 나간다. */
    @Query("""
            select c from Comment c
            where c.parentId in :parentIds and c.status <> :deleted
            order by c.createdAt asc, c.id asc
            """)
    List<Comment> findReplies(@Param("parentIds") Collection<Long> parentIds,
                              @Param("deleted") CommentStatus deleted);

    long countByDiscussionIdAndStatus(Long discussionId, CommentStatus status);

    /** 문제별 보이는 댓글 수 — 문제 목록이 한 쪽(20건)의 수를 한 번에 묻는다. */
    @Query("""
            select d.problemId as problemId, count(c) as cnt
            from Comment c join Discussion d on d.id = c.discussionId
            where d.problemId in :problemIds and c.status = :visible
            group by d.problemId
            """)
    List<ProblemCommentCount> countByProblemIds(@Param("problemIds") Collection<Long> problemIds,
                                                @Param("visible") CommentStatus visible);

    interface ProblemCommentCount {
        Long getProblemId();

        long getCnt();
    }
}
```

- [ ] **Step 7: `User`에 닉네임을 더한다**

`src/main/java/project/study/study_project/user/domain/User.java` — `username` 필드 선언 바로 아래에 넣는다.

```java
    /**
     * 토론에서 보이는 이름(V21). 첫 댓글을 쓸 때 정하므로 그 전에는 {@code null}이다.
     *
     * <p>{@code username}을 그대로 보여 주지 않는 이유: 로그인 아이디가 공개되면 남이 그 아이디로
     * 로그인을 시도할 수 있다.
     */
    @Column(unique = true, length = 12)
    private String nickname;
```

같은 파일의 `changePassword` 메서드 아래에 넣는다.

```java
    /** 닉네임 설정·변경. 형식과 중복은 서비스가 본다. */
    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }
```

`src/main/java/project/study/study_project/user/repository/UserRepository.java` — `findByUsername` 아래에 넣는다.

```java
    /** 닉네임 중복 검사. 자기 자신은 뺀다 — 같은 이름으로 다시 저장하는 것은 중복이 아니다. */
    boolean existsByNicknameAndIdNot(String nickname, Long id);
```

- [ ] **Step 8: 오류 코드를 더한다**

`src/main/java/project/study/study_project/global/exception/ErrorCode.java` — `REPORT_003` 줄의 끝 `;`를 `,`로 바꾸고 그 아래에 넣는다.

```java
    // 문제별 토론(V21). 409가 많은 이유는 REPORT와 같다 — "지금 상태와 부딪힌다"는 안내다.
    DISCUSSION_001("DISCUSSION_001", HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다."),
    DISCUSSION_002("DISCUSSION_002", HttpStatus.FORBIDDEN, "문제를 풀면 참여할 수 있습니다."),
    // 화면이 이 코드를 받으면 닉네임 입력을 띄우고, 정한 뒤 같은 요청을 다시 보낸다.
    DISCUSSION_003("DISCUSSION_003", HttpStatus.CONFLICT, "닉네임을 먼저 정해 주세요."),
    DISCUSSION_004("DISCUSSION_004", HttpStatus.CONFLICT, "이미 쓰는 이름입니다."),
    DISCUSSION_005("DISCUSSION_005", HttpStatus.FORBIDDEN, "내가 쓴 댓글만 고치거나 지울 수 있습니다."),
    DISCUSSION_006("DISCUSSION_006", HttpStatus.CONFLICT, "가려지거나 삭제된 댓글입니다."),
    DISCUSSION_007("DISCUSSION_007", HttpStatus.CONFLICT, "이미 신고한 댓글입니다."),
    DISCUSSION_008("DISCUSSION_008", HttpStatus.NOT_FOUND, "신고를 찾을 수 없습니다."),
    DISCUSSION_009("DISCUSSION_009", HttpStatus.CONFLICT, "이미 처리된 신고입니다.");
```

`docs/04-response-format.md` — 오류 코드 표의 마지막 줄 아래에 넣는다.

```markdown
| `DISCUSSION_001` | 404 | 댓글 없음 |
| `DISCUSSION_002` | 403 | 안 푼 문제의 토론에 쓰기 |
| `DISCUSSION_003` | 409 | 닉네임 없이 쓰기 → 화면이 닉네임 입력을 띄움 |
| `DISCUSSION_004` | 409 | 닉네임 중복 |
| `DISCUSSION_005` | 403 | 남의 댓글 수정·삭제 |
| `DISCUSSION_006` | 409 | 가려지거나 삭제된 댓글에 답글·수정·신고 |
| `DISCUSSION_007` | 409 | 같은 댓글을 두 번 신고 |
| `DISCUSSION_008` | 404 | 신고 없음 (관리자) |
| `DISCUSSION_009` | 409 | 이미 처리된 신고 (관리자) |
```

- [ ] **Step 9: 통과를 확인한다**

Run: `.\gradlew.bat test --tests "*DiscussionSchemaIntegrationTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, 테스트 4개 통과

- [ ] **Step 10: 전체 테스트로 기존 기능이 깨지지 않았는지 본다**

Run: `.\gradlew.bat test --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 11: 커밋한다**

```powershell
git add src/main/resources/db/migration/V21__problem_discussion.sql src/main/java/project/study/study_project/discussion src/main/java/project/study/study_project/user src/main/java/project/study/study_project/global/exception/ErrorCode.java docs/04-response-format.md src/test/java/project/study/study_project/discussion
git commit -F <메시지 파일>
```

메시지: `feat(discussion): 토론방·댓글·신고 표와 엔티티를 만든다 (V21)`

---

### Task 2: 닉네임

**Files:**
- Create: `src/main/java/project/study/study_project/user/dto/NicknameRequest.java`
- Create: `src/main/java/project/study/study_project/user/dto/NicknameResponse.java`
- Modify: `src/main/java/project/study/study_project/user/service/AccountService.java`
- Modify: `src/main/java/project/study/study_project/user/controller/AccountController.java`
- Modify: `src/main/resources/static/mypage.html`
- Test: `src/test/java/project/study/study_project/discussion/NicknameIntegrationTest.java`

**Interfaces:**
- Consumes: `User.changeNickname(String)`, `User.getNickname()`, `UserRepository.existsByNicknameAndIdNot(String, Long)`, `ErrorCode.DISCUSSION_004`
- Produces: `GET /api/me/nickname` → `{"nickname": "민수" | null}`, `PUT /api/me/nickname` 본문 `{"nickname": "민수"}` → 같은 모양

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/project/study/study_project/discussion/NicknameIntegrationTest.java`

```java
package project.study.study_project.discussion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class NicknameIntegrationTest {

    private static final String PATH = "/api/me/nickname";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("비로그인은 닉네임을 볼 수도 정할 수도 없다")
    void requiresLogin() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(PATH).contentType("application/json").content(body("민수")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("처음에는 비어 있고, 정하면 그 값이 돌아온다")
    void setsNickname() throws Exception {
        String token = bearer();

        mockMvc.perform(get(PATH).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").doesNotExist());

        mockMvc.perform(put(PATH).header("Authorization", token)
                        .contentType("application/json").content(body("민수_" + suffix())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").exists());
    }

    @Test
    @DisplayName("남이 쓰는 이름은 409 DISCUSSION_004")
    void rejectsDuplicate() throws Exception {
        String name = "dup" + suffix();
        mockMvc.perform(put(PATH).header("Authorization", bearer())
                        .contentType("application/json").content(body(name)))
                .andExpect(status().isOk());

        mockMvc.perform(put(PATH).header("Authorization", bearer())
                        .contentType("application/json").content(body(name)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_004"));
    }

    /** 화면에서 두 사람이 구분되지 않는다 — user 표의 콜레이션(ai_ci)이 같은 값으로 본다. */
    @Test
    @DisplayName("대소문자만 다른 이름도 중복이다")
    void rejectsCaseOnlyDifference() throws Exception {
        String name = "Case" + suffix();
        mockMvc.perform(put(PATH).header("Authorization", bearer())
                        .contentType("application/json").content(body(name)))
                .andExpect(status().isOk());

        mockMvc.perform(put(PATH).header("Authorization", bearer())
                        .contentType("application/json").content(body(name.toLowerCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_004"));
    }

    @Test
    @DisplayName("같은 이름으로 다시 저장하는 것은 중복이 아니다")
    void allowsSavingOwnNicknameAgain() throws Exception {
        String token = bearer();
        String name = "same" + suffix();
        mockMvc.perform(put(PATH).header("Authorization", token)
                        .contentType("application/json").content(body(name)))
                .andExpect(status().isOk());
        mockMvc.perform(put(PATH).header("Authorization", token)
                        .contentType("application/json").content(body(name)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("2~12자, 한글·영문·숫자·밑줄만 받는다")
    void validatesFormat() throws Exception {
        String token = bearer();
        for (String bad : new String[]{"가", "열세글자를넘기는아주긴닉네임", "공백 있음", "<b>굵게</b>", ""}) {
            mockMvc.perform(put(PATH).header("Authorization", token)
                            .contentType("application/json").content(body(bad)))
                    .andExpect(status().isBadRequest());
        }
    }

    private String body(String nickname) {
        return "{\"nickname\":\"%s\"}".formatted(nickname);
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    private String bearer() {
        User user = userRepository.save(User.builder()
                .username("nick" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash(passwordEncoder.encode("password123"))
                .role(Role.USER)
                .build());
        return "Bearer " + jwtTokenProvider.createToken(user.getId(), Role.USER);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `.\gradlew.bat test --tests "*NicknameIntegrationTest" --console=plain`
Expected: FAIL — `requiresLogin` 외의 테스트가 404(경로 없음) 또는 405

- [ ] **Step 3: DTO를 쓴다**

`src/main/java/project/study/study_project/user/dto/NicknameRequest.java`

```java
package project.study.study_project.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 닉네임 설정 요청.
 *
 * <p>글자를 좁힌 이유: 공백과 기호를 받으면 "민수"와 "민수 "처럼 눈으로 구분되지 않는 이름이 생긴다.
 */
public record NicknameRequest(
        @NotBlank(message = "닉네임을 입력해 주세요.")
        @Pattern(regexp = "^[가-힣A-Za-z0-9_]{2,12}$",
                message = "닉네임은 2~12자의 한글·영문·숫자·밑줄만 쓸 수 있습니다.")
        String nickname
) {
}
```

`src/main/java/project/study/study_project/user/dto/NicknameResponse.java`

```java
package project.study.study_project.user.dto;

/** @param nickname 아직 안 정했으면 {@code null} */
public record NicknameResponse(String nickname) {
}
```

- [ ] **Step 4: 서비스에 닉네임을 더한다**

`src/main/java/project/study/study_project/user/service/AccountService.java` — `changePassword` 메서드 위에 넣는다. import에 `org.springframework.dao.DataIntegrityViolationException`을 더한다.

```java
    @Transactional(readOnly = true)
    public String getNickname(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_003))
                .getNickname();
    }

    /**
     * 닉네임 설정·변경.
     *
     * <p>중복을 두 겹으로 막는다. 먼저 세어 보고(안내 문구를 주려고), 그 사이 끼어든 요청은
     * 유일 제약이 막는다. 제약 위반을 같은 코드로 바꾸지 않으면 같은 상황이 어떨 땐 안내, 어떨 땐 500이 된다.
     */
    @Transactional
    public String changeNickname(Long userId, String nickname) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_003));
        if (userRepository.existsByNicknameAndIdNot(nickname, userId)) {
            throw new BusinessException(ErrorCode.DISCUSSION_004);
        }
        try {
            user.changeNickname(nickname);
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DISCUSSION_004);
        }
        log.info("닉네임 변경: userId={}", userId);
        return nickname;
    }
```

- [ ] **Step 5: 컨트롤러에 경로를 더한다**

`src/main/java/project/study/study_project/user/controller/AccountController.java` — `changePassword` 메서드 위에 넣는다. import에 `project.study.study_project.user.dto.NicknameRequest`, `NicknameResponse`를 더한다.

```java
    /** 지금 닉네임. 아직 안 정했으면 {@code nickname}이 null이다. */
    @GetMapping("/nickname")
    public ApiResponse<NicknameResponse> nickname(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(new NicknameResponse(accountService.getNickname(userId)));
    }

    /** 닉네임 설정·변경. 토론에서 글쓴이로 보이는 이름이다. */
    @PutMapping("/nickname")
    public ApiResponse<NicknameResponse> changeNickname(@AuthenticationPrincipal Long userId,
                                                        @Valid @RequestBody NicknameRequest request) {
        return ApiResponse.ok(new NicknameResponse(accountService.changeNickname(userId, request.nickname())));
    }
```

- [ ] **Step 6: 통과를 확인한다**

Run: `.\gradlew.bat test --tests "*NicknameIntegrationTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, 테스트 6개 통과

- [ ] **Step 7: 마이페이지에 닉네임 칸을 넣는다**

`src/main/resources/static/mypage.html` — `<div class="setting-row stacked" id="pwPanel">` 바로 위에 넣는다.

```html
  <!-- 닉네임(V21). 비밀번호보다 위에 두는 이유: 셋 중 가장 자주 만지고 가장 덜 위험하다. -->
  <div class="setting-row stacked" id="nickPanel">
    <b class="setting-head">닉네임</b>
    <div class="meta">토론에서 글쓴이로 보이는 이름입니다. 로그인 아이디는 다른 사람에게 보이지 않습니다.</div>
    <div id="nickMessage" role="alert" aria-live="assertive"></div>
    <form id="nickForm" style="margin-top:10px; max-width:420px">
      <div class="field">
        <label for="nickInput">닉네임 (2~12자, 한글·영문·숫자·밑줄)</label>
        <input type="text" id="nickInput" required minlength="2" maxlength="12"
               pattern="[가-힣A-Za-z0-9_]{2,12}" autocomplete="nickname">
      </div>
      <button type="submit" id="nickSubmit">닉네임 저장</button>
    </form>
  </div>
```

같은 파일 `<script>` 안, 비밀번호 폼을 연결하는 함수 정의 바로 위에 넣는다.

```html
/* 닉네임 — 지금 값을 채워 두고, 저장하면 그 자리에서 알린다. */
(async function initNickname() {
  const input = document.getElementById("nickInput");
  const msg = document.getElementById("nickMessage");
  const btn = document.getElementById("nickSubmit");
  try {
    const { nickname } = await api("/api/me/nickname");
    input.value = nickname || "";
  } catch (e) {
    msg.innerHTML = `<div class="alert error">${escapeHtml(e.message)}</div>`;
  }

  document.getElementById("nickForm").addEventListener("submit", async e => {
    e.preventDefault();
    btn.disabled = true;
    msg.innerHTML = "";
    try {
      const { nickname } = await api("/api/me/nickname", {
        method: "PUT", body: JSON.stringify({ nickname: input.value }) });
      input.value = nickname;
      msg.innerHTML = `<div class="alert" style="background:var(--correct-bg); color:var(--correct-fg)">
        닉네임을 저장했습니다.</div>`;
    } catch (e2) {
      msg.innerHTML = `<div class="alert error">${escapeHtml(e2.message)}</div>`;
    } finally {
      btn.disabled = false;
    }
  });
})();
```

- [ ] **Step 8: 브라우저로 확인한다**

`.claude/skills/verify`의 순서로 앱을 띄운다. 8080이 이미 쓰이고 있으면 `.\gradlew.bat bootRun "--args=--server.port=8081"`로 띄운다.

1. 테스트 계정으로 로그인하고 `/mypage.html`을 연다. 닉네임 칸이 비어 있다.
2. `verify_01`을 넣고 저장한다. "닉네임을 저장했습니다."가 뜬다.
3. 새로 고친다. 칸에 `verify_01`이 들어 있다.
4. `가`를 넣고 저장한다. 브라우저의 형식 안내가 뜨고 요청이 나가지 않는다.

- [ ] **Step 9: 커밋한다**

```powershell
git add src/main/java/project/study/study_project/user src/main/resources/static/mypage.html src/test/java/project/study/study_project/discussion/NicknameIntegrationTest.java
git commit -F <메시지 파일>
```

메시지: `feat(discussion): 닉네임을 정하고 바꾸는 API와 마이페이지 칸을 더한다`

---

### Task 3: 댓글 읽기·쓰기·수정·삭제

**Files:**
- Create: `src/main/java/project/study/study_project/discussion/dto/CommentWriteRequest.java`
- Create: `src/main/java/project/study/study_project/discussion/dto/CommentEditRequest.java`
- Create: `src/main/java/project/study/study_project/discussion/dto/CommentItem.java`
- Create: `src/main/java/project/study/study_project/discussion/dto/CommentListResponse.java`
- Create: `src/main/java/project/study/study_project/discussion/service/CommentService.java`
- Create: `src/main/java/project/study/study_project/discussion/controller/CommentController.java`
- Create: `src/main/java/project/study/study_project/discussion/controller/MyCommentController.java`
- Modify: `src/main/java/project/study/study_project/quiz/repository/SubmissionRepository.java`
- Test: `src/test/java/project/study/study_project/discussion/CommentIntegrationTest.java`
- Test: `src/test/java/project/study/study_project/discussion/DiscussionConcurrencyTest.java`

**Interfaces:**
- Consumes: Task 1의 저장소·엔티티·오류 코드, Task 2의 `User.getNickname()`
- Produces:
  - `CommentService.list(Long problemId, Long viewerId, int page)` → `CommentListResponse`
  - `CommentService.write(Long userId, CommentWriteRequest)` → `CommentItem`
  - `CommentService.edit(Long userId, Long commentId, String body)` → `CommentItem`
  - `CommentService.delete(Long userId, Long commentId)`
  - `CommentService.counts(Collection<Long> problemIds)` → `Map<Long, Long>`
  - `CommentWriteRequest(Long problemId, Long parentId, String body)`
  - `CommentItem(Long id, String nickname, String body, CommentStatus status, boolean mine, boolean edited, LocalDateTime createdAt, List<CommentItem> replies)`
  - `CommentListResponse(boolean solved, boolean canWrite, long total, boolean hasNext, List<CommentItem> comments)`
  - `GET /api/quiz/{problemId}/comments?page=0`, `GET /api/quiz/comment-counts?problemIds=1,2`, `POST /api/me/comments`, `PUT /api/me/comments/{id}`, `DELETE /api/me/comments/{id}`

- [ ] **Step 1: 실패하는 통합 테스트를 쓴다**

`src/test/java/project/study/study_project/discussion/CommentIntegrationTest.java`

```java
package project.study.study_project.discussion;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.discussion.domain.Comment;
import project.study.study_project.discussion.repository.CommentRepository;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.domain.Submission;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.repository.SubmissionRepository;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.dto.WithdrawRequest;
import project.study.study_project.user.repository.UserRepository;
import project.study.study_project.user.service.AccountService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 문제별 토론의 경계 — 누가 읽고 누가 쓸 수 있는가, 지운 글과 가린 글이 어떻게 보이는가.
 *
 * <p>요청 제한은 끈다. 한 테스트가 쓰기를 여러 번 불러 분당 5건에 걸리면 무관한 실패가 난다.
 */
@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class CommentIntegrationTest {

    private static final String WRITE = "/api/me/comments";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProblemRepository problemRepository;
    @Autowired
    private SubmissionRepository submissionRepository;
    @Autowired
    private CommentRepository commentRepository;
    @Autowired
    private AccountService accountService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private EntityManager em;

    /* ── 읽기 ───────────────────────────────────────────── */

    @Test
    @DisplayName("비로그인도 읽을 수 있다 — 글이 없으면 빈 목록, solved는 false")
    void anonymousCanRead() throws Exception {
        Problem problem = saveProblem();

        mockMvc.perform(get(listPath(problem)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.solved").value(false))
                .andExpect(jsonPath("$.data.canWrite").value(false))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.comments", hasSize(0)));
    }

    @Test
    @DisplayName("없는 문제의 토론은 404 QUIZ_001")
    void unknownProblem() throws Exception {
        mockMvc.perform(get("/api/quiz/999999999/comments"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("QUIZ_001"));
    }

    /* ── 쓰기 권한 ───────────────────────────────────────── */

    @Test
    @DisplayName("비로그인은 쓸 수 없다")
    void writeRequiresLogin() throws Exception {
        mockMvc.perform(post(WRITE).contentType("application/json")
                        .content(writeBody(saveProblem().getId(), null, "글")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("안 푼 문제에는 쓸 수 없다 — 403 DISCUSSION_002")
    void unsolvedCannotWrite() throws Exception {
        Problem problem = saveProblem();
        User user = saveUser(Role.USER, true);

        mockMvc.perform(post(WRITE).header("Authorization", bearer(user))
                        .contentType("application/json").content(writeBody(problem.getId(), null, "글")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_002"));
    }

    @Test
    @DisplayName("닉네임이 없으면 409 DISCUSSION_003 — 화면이 닉네임 입력을 띄운다")
    void needsNickname() throws Exception {
        Problem problem = saveProblem();
        User user = saveUser(Role.USER, false);
        solve(user, problem);

        mockMvc.perform(post(WRITE).header("Authorization", bearer(user))
                        .contentType("application/json").content(writeBody(problem.getId(), null, "글")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_003"));
    }

    @Test
    @DisplayName("틀리게 풀었어도 쓸 수 있다 — 제출이 있으면 푼 것이다")
    void wrongAnswerStillCounts() throws Exception {
        Problem problem = saveProblem();
        User user = saveUser(Role.USER, true);
        submissionRepository.save(Submission.of(user.getId(), problem, "X", false));

        mockMvc.perform(post(WRITE).header("Authorization", bearer(user))
                        .contentType("application/json").content(writeBody(problem.getId(), null, "왜 틀렸을까요")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("관리자는 풀지 않아도 쓸 수 있다")
    void adminWritesWithoutSolving() throws Exception {
        Problem problem = saveProblem();
        User admin = saveUser(Role.ADMIN, true);

        mockMvc.perform(post(WRITE).header("Authorization", bearer(admin))
                        .contentType("application/json").content(writeBody(problem.getId(), null, "안내드립니다")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("본문이 비었거나 1,000자를 넘으면 400")
    void validatesBody() throws Exception {
        Problem problem = saveProblem();
        User user = saveUser(Role.USER, true);
        solve(user, problem);
        String token = bearer(user);

        mockMvc.perform(post(WRITE).header("Authorization", token)
                        .contentType("application/json").content(writeBody(problem.getId(), null, "   ")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(WRITE).header("Authorization", token)
                        .contentType("application/json").content(writeBody(problem.getId(), null, "가".repeat(1001))))
                .andExpect(status().isBadRequest());
    }

    /* ── 쓰기와 목록 ─────────────────────────────────────── */

    @Test
    @DisplayName("쓴 글이 목록에 닉네임과 함께 보이고, 내 글에는 mine이 붙는다")
    void writtenCommentAppears() throws Exception {
        Problem problem = saveProblem();
        User writer = saveUser(Role.USER, true);
        solve(writer, problem);

        mockMvc.perform(post(WRITE).header("Authorization", bearer(writer))
                        .contentType("application/json").content(writeBody(problem.getId(), null, "첫 글")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.nickname").value(writer.getNickname()))
                .andExpect(jsonPath("$.data.status").value("VISIBLE"));

        mockMvc.perform(get(listPath(problem)).header("Authorization", bearer(writer)))
                .andExpect(jsonPath("$.data.solved").value(true))
                .andExpect(jsonPath("$.data.canWrite").value(true))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.comments[0].body").value("첫 글"))
                .andExpect(jsonPath("$.data.comments[0].mine").value(true));

        mockMvc.perform(get(listPath(problem)))
                .andExpect(jsonPath("$.data.comments[0].mine").value(false));
    }

    @Test
    @DisplayName("답글의 답글은 원글 아래로 붙는다 — 한 단계까지만")
    void replyToReplyAttachesToRoot() throws Exception {
        Problem problem = saveProblem();
        User user = saveUser(Role.USER, true);
        solve(user, problem);
        String token = bearer(user);

        long rootId = write(token, problem.getId(), null, "원글");
        long replyId = write(token, problem.getId(), rootId, "답글");
        write(token, problem.getId(), replyId, "답글의 답글");

        mockMvc.perform(get(listPath(problem)))
                .andExpect(jsonPath("$.data.comments", hasSize(1)))
                .andExpect(jsonPath("$.data.comments[0].replies", hasSize(2)))
                .andExpect(jsonPath("$.data.comments[0].replies[1].body").value("답글의 답글"))
                .andExpect(jsonPath("$.data.total").value(3));
    }

    /** 받아 주면 한 방의 답글이 다른 방의 원글에 매달린다. */
    @Test
    @DisplayName("다른 문제의 댓글을 부모로 주면 404 DISCUSSION_001")
    void parentFromAnotherProblemIsRejected() throws Exception {
        Problem a = saveProblem();
        Problem b = saveProblem();
        User user = saveUser(Role.USER, true);
        solve(user, a);
        solve(user, b);
        String token = bearer(user);
        long commentOnB = write(token, b.getId(), null, "B의 글");

        mockMvc.perform(post(WRITE).header("Authorization", token)
                        .contentType("application/json").content(writeBody(a.getId(), commentOnB, "A에 다는 답글")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_001"));
    }

    /* ── 수정·삭제 ───────────────────────────────────────── */

    @Test
    @DisplayName("내 글만 고칠 수 있고, 고치면 edited가 붙는다")
    void editOnlyOwn() throws Exception {
        Problem problem = saveProblem();
        User writer = saveUser(Role.USER, true);
        User other = saveUser(Role.USER, true);
        solve(writer, problem);
        long id = write(bearer(writer), problem.getId(), null, "처음");

        mockMvc.perform(put(WRITE + "/" + id).header("Authorization", bearer(other))
                        .contentType("application/json").content("{\"body\":\"남이 고침\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_005"));

        mockMvc.perform(put(WRITE + "/" + id).header("Authorization", bearer(writer))
                        .contentType("application/json").content("{\"body\":\"고침\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.body").value("고침"))
                .andExpect(jsonPath("$.data.edited").value(true));
    }

    @Test
    @DisplayName("답글이 달린 글을 지우면 자리가 남고 본문은 안 나간다")
    void deletedWithRepliesKeepsPlaceholder() throws Exception {
        Problem problem = saveProblem();
        User user = saveUser(Role.USER, true);
        solve(user, problem);
        String token = bearer(user);
        long rootId = write(token, problem.getId(), null, "지울 글");
        write(token, problem.getId(), rootId, "답글");

        mockMvc.perform(delete(WRITE + "/" + rootId).header("Authorization", token))
                .andExpect(status().isOk());

        mockMvc.perform(get(listPath(problem)))
                .andExpect(jsonPath("$.data.comments", hasSize(1)))
                .andExpect(jsonPath("$.data.comments[0].status").value("DELETED"))
                .andExpect(jsonPath("$.data.comments[0].body").doesNotExist())
                .andExpect(jsonPath("$.data.comments[0].nickname").doesNotExist())
                .andExpect(jsonPath("$.data.comments[0].replies", hasSize(1)))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @DisplayName("답글이 없는 글을 지우면 목록에서 빠진다")
    void deletedWithoutRepliesDisappears() throws Exception {
        Problem problem = saveProblem();
        User user = saveUser(Role.USER, true);
        solve(user, problem);
        String token = bearer(user);
        long id = write(token, problem.getId(), null, "지울 글");

        mockMvc.perform(delete(WRITE + "/" + id).header("Authorization", token))
                .andExpect(status().isOk());

        mockMvc.perform(get(listPath(problem)))
                .andExpect(jsonPath("$.data.comments", hasSize(0)))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("가려진 글은 자리만 보이고 본문과 닉네임은 응답에 없다")
    void hiddenBodyIsNotExposed() throws Exception {
        Problem problem = saveProblem();
        User user = saveUser(Role.USER, true);
        solve(user, problem);
        long id = write(bearer(user), problem.getId(), null, "가려질 글");
        Comment comment = commentRepository.findById(id).orElseThrow();
        comment.hide();
        em.flush();

        mockMvc.perform(get(listPath(problem)))
                .andExpect(jsonPath("$.data.comments[0].status").value("HIDDEN"))
                .andExpect(jsonPath("$.data.comments[0].body").doesNotExist())
                .andExpect(jsonPath("$.data.comments[0].nickname").doesNotExist())
                .andExpect(jsonPath("$.data.total").value(0));

        mockMvc.perform(post(WRITE).header("Authorization", bearer(user))
                        .contentType("application/json").content(writeBody(problem.getId(), id, "가려진 글에 답글")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_006"));
    }

    /* ── 탈퇴 ───────────────────────────────────────────── */

    /** 탈퇴가 외래키에 걸려 실패하지 않고, 남은 글은 글쓴이가 빈 채로 보인다. */
    @Test
    @DisplayName("탈퇴해도 댓글은 남고 글쓴이만 비어 나간다")
    void withdrawKeepsComments() throws Exception {
        Problem problem = saveProblem();
        User writer = saveUser(Role.USER, true);
        solve(writer, problem);
        write(bearer(writer), problem.getId(), null, "남을 글");
        em.flush();

        accountService.withdraw(writer.getId(), new WithdrawRequest("password123"));
        em.clear();

        mockMvc.perform(get(listPath(problem)))
                .andExpect(jsonPath("$.data.comments", hasSize(1)))
                .andExpect(jsonPath("$.data.comments[0].body").value("남을 글"))
                .andExpect(jsonPath("$.data.comments[0].nickname").doesNotExist());
    }

    /* ── 문제별 개수 ─────────────────────────────────────── */

    @Test
    @DisplayName("문제별 댓글 수 — 글이 없는 문제는 응답에 없다")
    void commentCounts() throws Exception {
        Problem with = saveProblem();
        Problem without = saveProblem();
        User user = saveUser(Role.USER, true);
        solve(user, with);
        write(bearer(user), with.getId(), null, "하나");
        write(bearer(user), with.getId(), null, "둘");

        mockMvc.perform(get("/api/quiz/comment-counts")
                        .param("problemIds", with.getId() + "," + without.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data['" + with.getId() + "']").value(2))
                .andExpect(jsonPath("$.data['" + without.getId() + "']").doesNotExist());
    }

    /* ── 재료 ───────────────────────────────────────────── */

    private String listPath(Problem problem) {
        return "/api/quiz/" + problem.getId() + "/comments";
    }

    private String writeBody(Long problemId, Long parentId, String body) {
        return "{\"problemId\":%d,\"parentId\":%s,\"body\":\"%s\"}".formatted(problemId, parentId, body);
    }

    private long write(String token, Long problemId, Long parentId, String body) throws Exception {
        String response = mockMvc.perform(post(WRITE).header("Authorization", token)
                        .contentType("application/json").content(writeBody(problemId, parentId, body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int at = response.indexOf("\"id\":");
        return Long.parseLong(response.substring(at + 5, response.indexOf(',', at)).trim());
    }

    private User saveUser(Role role, boolean withNickname) {
        String key = UUID.randomUUID().toString().substring(0, 8);
        User user = User.builder()
                .username("disc" + key)
                .passwordHash(passwordEncoder.encode("password123"))
                .role(role)
                .build();
        if (withNickname) {
            user.changeNickname("닉" + key);
        }
        return userRepository.save(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenProvider.createToken(user.getId(), user.getRole());
    }

    private void solve(User user, Problem problem) {
        submissionRepository.save(Submission.of(user.getId(), problem, "O", true));
    }

    private Problem saveProblem() {
        return problemRepository.save(Problem.create(
                TestDomains.NETWORK, Difficulty.BEGINNER, ProblemType.OX,
                "TCP 3-way handshake",
                "TCP 연결은 3번의 패킷 교환으로 시작한다.", "O", "SYN → SYN+ACK → ACK", null));
    }
}
```

`WithdrawRequest`의 생성자 인자가 비밀번호 하나가 아니면 그 record의 정의에 맞춰 `new WithdrawRequest(...)` 줄을 고친다(`src/main/java/project/study/study_project/user/dto/WithdrawRequest.java`).

- [ ] **Step 2: 실패를 확인한다**

Run: `.\gradlew.bat test --tests "*CommentIntegrationTest" --console=plain`
Expected: 테스트 대부분 FAIL — `anonymousCanRead`가 404(경로 없음)

- [ ] **Step 3: "풀었는지" 조회를 더한다**

`src/main/java/project/study/study_project/quiz/repository/SubmissionRepository.java` — `existsByProblemId` 아래에 넣는다.

```java
    /** 토론 쓰기 권한 — 이 사용자가 이 문제를 한 번이라도 제출했는가. 맞혔는지는 보지 않는다. */
    boolean existsByUserIdAndProblem_Id(Long userId, Long problemId);
```

- [ ] **Step 4: DTO를 쓴다**

`src/main/java/project/study/study_project/discussion/dto/CommentWriteRequest.java`

```java
package project.study.study_project.discussion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param parentId 답글이면 그 대상 댓글 id. 답글에 다는 답글이면 서비스가 원글 id로 바꾼다
 */
public record CommentWriteRequest(
        @NotNull(message = "문제를 지정해 주세요.")
        Long problemId,
        Long parentId,
        @NotBlank(message = "내용을 입력해 주세요.")
        @Size(max = 1000, message = "댓글은 1,000자를 넘을 수 없습니다.")
        String body
) {
}
```

`src/main/java/project/study/study_project/discussion/dto/CommentEditRequest.java`

```java
package project.study.study_project.discussion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CommentEditRequest(
        @NotBlank(message = "내용을 입력해 주세요.")
        @Size(max = 1000, message = "댓글은 1,000자를 넘을 수 없습니다.")
        String body
) {
}
```

`src/main/java/project/study/study_project/discussion/dto/CommentItem.java`

```java
package project.study.study_project.discussion.dto;

import project.study.study_project.discussion.domain.Comment;
import project.study.study_project.discussion.domain.CommentStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 댓글 한 건의 화면용 표현.
 *
 * @param nickname 글쓴이 표시 이름. 탈퇴했거나 보이지 않는 글이면 {@code null}
 * @param body     보이지 않는 글(가림·삭제)이면 {@code null} — 화면에서만 가리면 응답을 열어 읽을 수 있다
 * @param replies  답글. 답글 자신에게는 늘 빈 목록이다
 */
public record CommentItem(
        Long id,
        String nickname,
        String body,
        CommentStatus status,
        boolean mine,
        boolean edited,
        LocalDateTime createdAt,
        List<CommentItem> replies
) {

    public static CommentItem of(Comment comment, String nickname, Long viewerId, List<CommentItem> replies) {
        boolean visible = comment.isVisible();
        return new CommentItem(
                comment.getId(),
                visible ? nickname : null,
                visible ? comment.getBody() : null,
                comment.getStatus(),
                visible && viewerId != null && viewerId.equals(comment.getUserId()),
                comment.getEditedAt() != null,
                comment.getCreatedAt(),
                replies);
    }
}
```

`src/main/java/project/study/study_project/discussion/dto/CommentListResponse.java`

```java
package project.study.study_project.discussion.dto;

import java.util.List;

/**
 * 한 문제의 토론 한 쪽.
 *
 * @param solved   보는 사람이 이 문제를 풀었는지. 화면이 토론을 접어 둘지 정한다
 * @param canWrite 쓸 수 있는지 — 풀었거나 관리자다
 * @param total    보이는 댓글·답글 수. 가려지거나 삭제된 글은 세지 않는다
 * @param hasNext  다음 쪽이 있는지
 */
public record CommentListResponse(
        boolean solved,
        boolean canWrite,
        long total,
        boolean hasNext,
        List<CommentItem> comments
) {
}
```

- [ ] **Step 5: 서비스를 쓴다**

`src/main/java/project/study/study_project/discussion/service/CommentService.java`

```java
package project.study.study_project.discussion.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.discussion.domain.Comment;
import project.study.study_project.discussion.domain.CommentStatus;
import project.study.study_project.discussion.dto.CommentItem;
import project.study.study_project.discussion.dto.CommentListResponse;
import project.study.study_project.discussion.dto.CommentWriteRequest;
import project.study.study_project.discussion.repository.CommentRepository;
import project.study.study_project.discussion.repository.DiscussionRepository;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.repository.SubmissionRepository;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 문제별 토론 — 읽기, 쓰기, 수정, 삭제.
 *
 * <p>읽기는 누구에게나 열려 있고 쓰기는 그 문제를 푼 사람만 한다. 그 판정을 화면이 아니라 여기서 한다 —
 * 화면에서만 막으면 주소를 직접 불러 쓸 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    /** 한 쪽의 댓글 수. 답글은 댓글에 딸려 전부 함께 간다. */
    private static final int PAGE_SIZE = 20;

    private final CommentRepository commentRepository;
    private final DiscussionRepository discussionRepository;
    private final ProblemRepository problemRepository;
    private final SubmissionRepository submissionRepository;
    private final UserRepository userRepository;

    /**
     * @param viewerId 보는 사람. 비로그인이면 {@code null}
     */
    @Transactional(readOnly = true)
    public CommentListResponse list(Long problemId, Long viewerId, int page) {
        requireProblem(problemId);
        User viewer = viewerId == null ? null : userRepository.findById(viewerId).orElse(null);
        boolean solved = viewer != null && submissionRepository.existsByUserIdAndProblem_Id(viewerId, problemId);
        boolean canWrite = solved || (viewer != null && viewer.getRole() == Role.ADMIN);

        Optional<Long> discussionId = discussionRepository.findIdByProblemId(problemId);
        if (discussionId.isEmpty()) {
            return new CommentListResponse(solved, canWrite, 0, false, List.of());
        }

        Page<Comment> threads = commentRepository.findThreads(
                discussionId.get(), CommentStatus.DELETED, PageRequest.of(Math.max(page, 0), PAGE_SIZE));
        List<Long> threadIds = threads.getContent().stream().map(Comment::getId).toList();
        List<Comment> replies = threadIds.isEmpty()
                ? List.of()
                : commentRepository.findReplies(threadIds, CommentStatus.DELETED);

        Map<Long, String> nicknames = nicknamesOf(threads.getContent(), replies);
        Map<Long, List<CommentItem>> repliesByParent = new LinkedHashMap<>();
        for (Comment reply : replies) {
            repliesByParent.computeIfAbsent(reply.getParentId(), id -> new ArrayList<>())
                    .add(CommentItem.of(reply, nicknames.get(reply.getUserId()), viewerId, List.of()));
        }
        List<CommentItem> items = threads.getContent().stream()
                .map(c -> CommentItem.of(c, nicknames.get(c.getUserId()), viewerId,
                        repliesByParent.getOrDefault(c.getId(), List.of())))
                .toList();

        long total = commentRepository.countByDiscussionIdAndStatus(discussionId.get(), CommentStatus.VISIBLE);
        return new CommentListResponse(solved, canWrite, total, threads.hasNext(), items);
    }

    @Transactional
    public CommentItem write(Long userId, CommentWriteRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_003));
        Long problemId = request.problemId();
        requireProblem(problemId);
        if (user.getRole() != Role.ADMIN
                && !submissionRepository.existsByUserIdAndProblem_Id(userId, problemId)) {
            throw new BusinessException(ErrorCode.DISCUSSION_002);
        }
        if (user.getNickname() == null) {
            throw new BusinessException(ErrorCode.DISCUSSION_003);
        }

        // 방은 첫 댓글 때 만든다. 두 문장의 이유는 DiscussionRepository 주석에 있다.
        discussionRepository.insertIfAbsent(problemId);
        Long discussionId = discussionRepository.findIdByProblemIdForShare(problemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_001));

        Long rootId = null;
        if (request.parentId() != null) {
            // 다른 방의 댓글은 "없는 댓글"로 답한다 — 받아 주면 답글이 다른 문제의 원글에 매달린다.
            Comment parent = commentRepository.findById(request.parentId())
                    .filter(p -> p.getDiscussionId().equals(discussionId))
                    .orElseThrow(() -> new BusinessException(ErrorCode.DISCUSSION_001));
            if (!parent.isVisible()) {
                throw new BusinessException(ErrorCode.DISCUSSION_006);
            }
            rootId = parent.getParentId() != null ? parent.getParentId() : parent.getId();
        }

        Comment saved = commentRepository.save(Comment.of(discussionId, userId, rootId, request.body().trim()));
        log.info("댓글 작성: problemId={} commentId={} reply={}", problemId, saved.getId(), rootId != null);
        return CommentItem.of(saved, user.getNickname(), userId, List.of());
    }

    @Transactional
    public CommentItem edit(Long userId, Long commentId, String body) {
        Comment comment = requireOwn(userId, commentId);
        if (!comment.isVisible()) {
            throw new BusinessException(ErrorCode.DISCUSSION_006);
        }
        comment.edit(body.trim());
        String nickname = userRepository.findById(userId).map(User::getNickname).orElse(null);
        return CommentItem.of(comment, nickname, userId, List.of());
    }

    /** 이미 지운 글을 또 지워도 오류가 아니다 — 두 번 눌린 삭제 버튼에 실패를 보여 줄 이유가 없다. */
    @Transactional
    public void delete(Long userId, Long commentId) {
        requireOwn(userId, commentId).delete();
    }

    /** 문제별 보이는 댓글 수. 글이 없는 문제는 맵에 없다. */
    @Transactional(readOnly = true)
    public Map<Long, Long> counts(Collection<Long> problemIds) {
        Map<Long, Long> counts = new HashMap<>();
        if (problemIds == null || problemIds.isEmpty()) {
            return counts;
        }
        commentRepository.countByProblemIds(problemIds, CommentStatus.VISIBLE)
                .forEach(row -> counts.put(row.getProblemId(), row.getCnt()));
        return counts;
    }

    private void requireProblem(Long problemId) {
        if (!problemRepository.existsById(problemId)) {
            throw new BusinessException(ErrorCode.QUIZ_001);
        }
    }

    /** 존재를 먼저 보고 주인을 본다 — 뒤집으면 없는 id에 "권한 없음"이 나간다. */
    private Comment requireOwn(Long userId, Long commentId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DISCUSSION_001));
        if (!userId.equals(comment.getUserId())) {
            throw new BusinessException(ErrorCode.DISCUSSION_005);
        }
        return comment;
    }

    /** 글쓴이 id → 닉네임. 탈퇴한 사용자는 id가 null이라 맵에 없다. */
    private Map<Long, String> nicknamesOf(List<Comment> threads, List<Comment> replies) {
        List<Long> userIds = java.util.stream.Stream.concat(threads.stream(), replies.stream())
                .map(Comment::getUserId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> nicknames = new HashMap<>();
        userRepository.findAllById(userIds).forEach(u -> nicknames.put(u.getId(), u.getNickname()));
        return nicknames;
    }
}
```

- [ ] **Step 6: 컨트롤러를 쓴다**

`src/main/java/project/study/study_project/discussion/controller/CommentController.java`

```java
package project.study.study_project.discussion.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.discussion.dto.CommentListResponse;
import project.study.study_project.discussion.service.CommentService;
import project.study.study_project.global.response.ApiResponse;

import java.util.List;
import java.util.Map;

/**
 * 토론 읽기 — 공개.
 *
 * <p>경로가 {@code /api/quiz} 아래인 이유: {@code GET /api/quiz/**}는 이미 공개 경로다
 * (SecurityConfig "화면과 문제는 누구나"). 문제에 딸린 토론도 같은 문을 쓴다.
 */
@RestController
@RequestMapping("/api/quiz")
@RequiredArgsConstructor
public class CommentController {

    /** 한 번에 물을 수 있는 문제 수 — 문제 목록의 한 쪽(20건)보다 넉넉하게 잡았다. */
    private static final int MAX_COUNT_IDS = 100;

    private final CommentService commentService;

    /** {@code userId}는 비로그인이면 null이다. 그때 solved·canWrite는 false로 나간다. */
    @GetMapping("/{problemId}/comments")
    public ApiResponse<CommentListResponse> list(@PathVariable Long problemId,
                                                 @AuthenticationPrincipal Long userId,
                                                 @RequestParam(defaultValue = "0") int page) {
        return ApiResponse.ok(commentService.list(problemId, userId, page));
    }

    /** 문제별 댓글 수. 예: {@code GET /api/quiz/comment-counts?problemIds=12,15,18} */
    @GetMapping("/comment-counts")
    public ApiResponse<Map<Long, Long>> counts(@RequestParam List<Long> problemIds) {
        List<Long> limited = problemIds.size() > MAX_COUNT_IDS ? problemIds.subList(0, MAX_COUNT_IDS) : problemIds;
        return ApiResponse.ok(commentService.counts(limited));
    }
}
```

`src/main/java/project/study/study_project/discussion/controller/MyCommentController.java`

```java
package project.study.study_project.discussion.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.discussion.dto.CommentEditRequest;
import project.study.study_project.discussion.dto.CommentItem;
import project.study.study_project.discussion.dto.CommentWriteRequest;
import project.study.study_project.discussion.service.CommentService;
import project.study.study_project.global.response.ApiResponse;

/**
 * 내 댓글 쓰기·수정·삭제 — 로그인 필수({@code /api/me/**}).
 *
 * <p>글쓴이 id는 토큰에서만 꺼낸다. 본문으로 받으면 남의 이름으로 쓸 수 있다.
 */
@RestController
@RequestMapping("/api/me/comments")
@RequiredArgsConstructor
public class MyCommentController {

    private final CommentService commentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CommentItem> write(@AuthenticationPrincipal Long userId,
                                          @Valid @RequestBody CommentWriteRequest request) {
        return ApiResponse.ok(commentService.write(userId, request));
    }

    @PutMapping("/{id}")
    public ApiResponse<CommentItem> edit(@AuthenticationPrincipal Long userId,
                                         @PathVariable Long id,
                                         @Valid @RequestBody CommentEditRequest request) {
        return ApiResponse.ok(commentService.edit(userId, id, request.body()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        commentService.delete(userId, id);
        return ApiResponse.ok(null);
    }
}
```

- [ ] **Step 7: 통과를 확인한다**

Run: `.\gradlew.bat test --tests "*CommentIntegrationTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, 테스트 17개 통과

`withdrawKeepsComments`가 외래키 오류로 실패하면 V21의 `fk_comment_user`가 `ON DELETE SET NULL`인지, `fk_comment_report_user`가 `ON DELETE CASCADE`인지 확인한다.

- [ ] **Step 8: 동시성 테스트를 쓴다**

`src/test/java/project/study/study_project/discussion/DiscussionConcurrencyTest.java`

```java
package project.study.study_project.discussion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.study_project.TestDomains;
import project.study.study_project.discussion.dto.CommentItem;
import project.study.study_project.discussion.dto.CommentWriteRequest;
import project.study.study_project.discussion.repository.DiscussionRepository;
import project.study.study_project.discussion.service.CommentService;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.domain.Submission;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.repository.SubmissionRepository;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 한 문제에 첫 댓글 둘이 동시에 온다 — 방은 하나만 생기고 댓글은 둘 다 저장돼야 한다.
 *
 * <p>{@code @Transactional}을 붙이지 않는다. 두 스레드가 서로의 커밋을 봐야 재현되므로 실제로
 * 커밋하고, 만든 것은 끝에 손으로 지운다.
 */
@SpringBootTest(properties = "ratelimit.enabled=false")
class DiscussionConcurrencyTest {

    @Autowired
    private CommentService commentService;
    @Autowired
    private DiscussionRepository discussionRepository;
    @Autowired
    private ProblemRepository problemRepository;
    @Autowired
    private SubmissionRepository submissionRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private TransactionTemplate tx;

    private Long problemId;
    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        tx.executeWithoutResult(status -> {
            // 제출은 문제를 RESTRICT로 붙잡고 있어 먼저 지운다. 방과 댓글은 문제와 함께 지워진다.
            userIds.forEach(submissionRepository::deleteAllByUserId);
            if (problemId != null) {
                problemRepository.deleteById(problemId);
            }
            userRepository.deleteAllById(userIds);
        });
    }

    @Test
    @DisplayName("첫 댓글 둘이 동시에 와도 방은 하나, 댓글은 둘")
    void twoFirstCommentsAtOnce() throws Exception {
        tx.executeWithoutResult(status -> {
            Problem problem = problemRepository.save(Problem.create(
                    TestDomains.NETWORK, Difficulty.BEGINNER, ProblemType.OX,
                    "동시성 확인용", "TCP 연결은 3번의 패킷 교환으로 시작한다.", "O", "SYN → SYN+ACK → ACK", null));
            problemId = problem.getId();
            for (int i = 0; i < 2; i++) {
                String key = UUID.randomUUID().toString().substring(0, 8);
                User user = User.builder().username("conc" + key)
                        .passwordHash(passwordEncoder.encode("password123")).role(Role.USER).build();
                user.changeNickname("동시" + key);
                userIds.add(userRepository.save(user).getId());
                submissionRepository.save(Submission.of(user.getId(), problem, "O", true));
            }
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CommentItem>> results = new ArrayList<>();
        for (Long userId : userIds) {
            results.add(pool.submit(() -> {
                start.await();
                return commentService.write(userId, new CommentWriteRequest(problemId, null, "동시에 쓴 첫 댓글"));
            }));
        }
        start.countDown();
        for (Future<CommentItem> result : results) {
            assertThat(result.get(15, TimeUnit.SECONDS).id()).isNotNull();
        }
        pool.shutdown();

        assertThat(discussionRepository.countByProblemId(problemId)).isEqualTo(1);
        assertThat(commentService.list(problemId, null, 0).total()).isEqualTo(2);
    }
}
```

- [ ] **Step 9: 동시성 테스트를 돌린다**

Run: `.\gradlew.bat test --tests "*DiscussionConcurrencyTest" --console=plain`
Expected: `BUILD SUCCESSFUL`

실패 확인: `CommentService.write`의 `findIdByProblemIdForShare`를 `findIdByProblemId`로 잠깐 바꾸고 다시 돌린다. 스냅샷 읽기로는 다른 트랜잭션이 만든 방이 안 보여 한쪽이 `QUIZ_001`로 실패할 수 있다(타이밍에 따라 통과하기도 한다). 확인한 뒤 원래대로 되돌린다.

- [ ] **Step 10: 커밋한다**

```powershell
git add src/main/java/project/study/study_project/discussion src/main/java/project/study/study_project/quiz/repository/SubmissionRepository.java src/test/java/project/study/study_project/discussion
git commit -F <메시지 파일>
```

메시지: `feat(discussion): 댓글 읽기·쓰기·수정·삭제 API를 만든다`

---

### Task 4: 댓글 작성 도배 방지

**Files:**
- Modify: `src/main/java/project/study/study_project/global/ratelimit/RateLimitProperties.java`
- Modify: `src/main/java/project/study/study_project/global/ratelimit/RateLimitFilter.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/project/study/study_project/global/ratelimit/RateLimitFilterTest.java`

**Interfaces:**
- Consumes: `POST /api/me/comments` 경로(Task 3)
- Produces: `RateLimitProperties.getCommentPolicy()` — 이름 `"comment"`, 기본 5/5/60

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/project/study/study_project/global/ratelimit/RateLimitFilterTest.java` — `props` 도우미를 아래로 바꾼다(생성자 인자가 셋 늘어난다).

```java
    // 운영 기본값과 같은 정책: auth 5/분, api 60/분, comment 5/분, enabled=true
    private RateLimitProperties props(boolean enabled) {
        return new RateLimitProperties(enabled, 5, 5, 60, 60, 60, 60, 5, 5, 60);
    }
```

같은 클래스에 테스트 둘을 더한다.

```java
    @Test
    @DisplayName("댓글 작성 POST는 comment 정책 + 사용자 id 키로 센다")
    void commentWriteUsesCommentPolicy() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        42L, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        doFilter(new MockHttpServletRequest("POST", "/api/me/comments"));

        ArgumentCaptor<RateLimitPolicy> policy = ArgumentCaptor.forClass(RateLimitPolicy.class);
        verify(limiter).tryConsume(eq("user:42"), policy.capture());
        assertThat(policy.getValue().name()).isEqualTo("comment");
        assertThat(policy.getValue().capacity()).isEqualTo(5);
    }

    @Test
    @DisplayName("댓글 수정·삭제와 읽기는 일반 api 정책이다 — 도배는 쓰기에서만 난다")
    void commentEditAndReadUseApiPolicy() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        42L, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        doFilter(new MockHttpServletRequest("PUT", "/api/me/comments/7"));
        doFilter(new MockHttpServletRequest("GET", "/api/quiz/3/comments"));

        ArgumentCaptor<RateLimitPolicy> policy = ArgumentCaptor.forClass(RateLimitPolicy.class);
        verify(limiter, times(2)).tryConsume(eq("user:42"), policy.capture());
        assertThat(policy.getAllValues()).allMatch(p -> p.name().equals("api"));
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `.\gradlew.bat test --tests "*RateLimitFilterTest" --console=plain`
Expected: 컴파일 실패 — `RateLimitProperties` 생성자 인자 수 불일치

- [ ] **Step 3: 설정 클래스에 정책을 더한다**

`src/main/java/project/study/study_project/global/ratelimit/RateLimitProperties.java` — 필드, 생성자, getter를 고친다.

필드에 더한다.

```java
    /** 댓글·답글 작성(V21). 불특정 다수가 쓰는 자리라 api(분당 60회)로는 도배를 못 막는다. */
    private final RateLimitPolicy commentPolicy;
```

생성자 인자 끝(`apiRefillPeriodSeconds` 뒤)에 더한다. 앞 인자 끝에 쉼표를 붙인다.

```java
            @Value("${ratelimit.comment.capacity:5}") int commentCapacity,
            @Value("${ratelimit.comment.refill-tokens:5}") int commentRefillTokens,
            @Value("${ratelimit.comment.refill-period-seconds:60}") int commentRefillPeriodSeconds
```

생성자 본문 끝에 더한다.

```java
        this.commentPolicy = new RateLimitPolicy("comment", commentCapacity, commentRefillTokens,
                commentRefillPeriodSeconds);
```

getter를 더한다.

```java
    public RateLimitPolicy getCommentPolicy() {
        return commentPolicy;
    }
```

클래스 주석의 "정책은 딱 2개만 둔다" 문단을 "정책은 셋이다"로 고치고 목록에 한 줄을 더한다.

```java
 *   <li><b>comment</b> — 댓글·답글 작성. 사람이 글을 쓰는 속도(분당 5건)면 넉넉하고, 도배에는 치명적이다.
```

- [ ] **Step 4: 필터가 댓글 작성을 가르게 한다**

`src/main/java/project/study/study_project/global/ratelimit/RateLimitFilter.java` — `AUTH_PATHS` 상수 아래에 더한다.

```java
    /** 댓글·답글 작성 경로. 수정·삭제는 넣지 않는다 — 도배는 새 글을 쓰는 데서만 난다. */
    private static final String COMMENT_WRITE_PATH = "/api/me/comments";
```

`doFilterInternal`의 정책 고르는 부분을 바꾼다. `} else {` 블록을 아래로 바꾼다.

```java
        } else {
            boolean isCommentWrite = "POST".equals(request.getMethod())
                    && COMMENT_WRITE_PATH.equals(request.getRequestURI());
            policy = isCommentWrite ? properties.getCommentPolicy() : properties.getApiPolicy();
            Long userId = authenticatedUserId();
            // 로그인 사용자는 id로(NAT 뒤 다수 사용자의 공정성 + IP를 바꿔도 한도 회피 불가),
            // 비로그인은 IP로 센다.
            bucketKey = (userId != null) ? "user:" + userId : "ip:" + clientIp(request);
        }
```

- [ ] **Step 5: 설정 파일에 값을 적는다**

`src/main/resources/application.yml` — `ratelimit.api` 블록 아래에 같은 들여쓰기로 넣는다.

```yaml
  comment:                       # 댓글·답글 작성 — 사용자당. 도배 방지(V21)
    capacity: 5
    refill-tokens: 5
    refill-period-seconds: 60    # = 분당 5회
```

- [ ] **Step 6: 통과를 확인한다**

Run: `.\gradlew.bat test --tests "*RateLimitFilterTest" --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 커밋한다**

```powershell
git add src/main/java/project/study/study_project/global/ratelimit src/main/resources/application.yml src/test/java/project/study/study_project/global/ratelimit/RateLimitFilterTest.java
git commit -F <메시지 파일>
```

메시지: `feat(ratelimit): 댓글 작성에 분당 5건 제한을 건다`

---

### Task 5: 신고와 관리자 판정

**Files:**
- Create: `src/main/java/project/study/study_project/discussion/domain/CommentReportReason.java`
- Create: `src/main/java/project/study/study_project/discussion/domain/CommentReport.java`
- Create: `src/main/java/project/study/study_project/discussion/repository/CommentReportRepository.java`
- Create: `src/main/java/project/study/study_project/discussion/dto/CommentReportRequest.java`
- Create: `src/main/java/project/study/study_project/discussion/dto/CommentReportItem.java`
- Create: `src/main/java/project/study/study_project/discussion/service/CommentReportService.java`
- Create: `src/main/java/project/study/study_project/discussion/controller/MyCommentReportController.java`
- Create: `src/main/java/project/study/study_project/admin/controller/AdminCommentController.java`
- Test: `src/test/java/project/study/study_project/discussion/CommentReportIntegrationTest.java`

**Interfaces:**
- Consumes: `Comment.hide()`, `Comment.restore()`, `CommentRepository`, `ErrorCode.DISCUSSION_001`·`006`~`009`, `project.study.study_project.report.domain.ReportStatus`(PENDING·ACCEPTED·DISMISSED, 기존 enum을 그대로 쓴다)
- Produces:
  - `POST /api/me/comment-reports` 본문 `{"commentId": 1, "reason": "ABUSE", "detail": "..."}` → 201
  - `GET /api/admin/comment-reports?status=PENDING&page=0` → `PageResponse<CommentReportItem>`
  - `GET /api/admin/comment-reports/pending-count` → `{"count": n}`
  - `POST /api/admin/comment-reports/{id}/dismiss` 본문 `{"note": "..."}`(선택)
  - `POST /api/admin/comments/{id}/hide`, `POST /api/admin/comments/{id}/restore` → `{"status": "HIDDEN" | "VISIBLE"}`
  - `CommentReportItem(Long id, Long commentId, String commentBody, CommentStatus commentStatus, String commentNickname, Long problemId, String problemTitle, CommentReportReason reason, String reasonLabel, String detail, ReportStatus status, String adminNote, LocalDateTime createdAt, LocalDateTime resolvedAt)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/project/study/study_project/discussion/CommentReportIntegrationTest.java`

```java
package project.study.study_project.discussion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.discussion.domain.Comment;
import project.study.study_project.discussion.repository.CommentReportRepository;
import project.study.study_project.discussion.repository.CommentRepository;
import project.study.study_project.discussion.repository.DiscussionRepository;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.report.domain.ReportStatus;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class CommentReportIntegrationTest {

    private static final String REPORT = "/api/me/comment-reports";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProblemRepository problemRepository;
    @Autowired
    private DiscussionRepository discussionRepository;
    @Autowired
    private CommentRepository commentRepository;
    @Autowired
    private CommentReportRepository reportRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("비로그인은 신고할 수 없다")
    void requiresLogin() throws Exception {
        mockMvc.perform(post(REPORT).contentType("application/json").content(body(saveComment().getId(), "ABUSE")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("문제를 풀지 않은 사람도 신고할 수 있다 — 읽을 수 있으면 신고도 할 수 있다")
    void anyLoggedInUserCanReport() throws Exception {
        mockMvc.perform(post(REPORT).header("Authorization", bearer(Role.USER))
                        .contentType("application/json").content(body(saveComment().getId(), "ABUSE")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.reasonLabel").exists());
    }

    @Test
    @DisplayName("같은 글을 두 번 신고하면 409 DISCUSSION_007")
    void rejectsDuplicate() throws Exception {
        Long commentId = saveComment().getId();
        String token = bearer(Role.USER);
        mockMvc.perform(post(REPORT).header("Authorization", token)
                        .contentType("application/json").content(body(commentId, "SPAM")))
                .andExpect(status().isCreated());

        mockMvc.perform(post(REPORT).header("Authorization", token)
                        .contentType("application/json").content(body(commentId, "ABUSE")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_007"));
    }

    @Test
    @DisplayName("없는 댓글은 404, 사유가 없으면 400")
    void validates() throws Exception {
        String token = bearer(Role.USER);
        mockMvc.perform(post(REPORT).header("Authorization", token)
                        .contentType("application/json").content(body(999_999_999L, "ABUSE")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_001"));
        mockMvc.perform(post(REPORT).header("Authorization", token)
                        .contentType("application/json")
                        .content("{\"commentId\":%d}".formatted(saveComment().getId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("신고함은 관리자만 본다")
    void reportBoxIsAdminOnly() throws Exception {
        mockMvc.perform(get("/api/admin/comment-reports")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/comment-reports").header("Authorization", bearer(Role.USER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/comment-reports").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    /** 개발 DB에 사람이 남긴 신고가 있어도 깨지지 않게 절대값이 아니라 변화량을 본다. */
    @Test
    @DisplayName("가리면 글이 HIDDEN이 되고, 그 글의 대기 신고가 모두 인정으로 바뀐다")
    void hideAcceptsPendingReports() throws Exception {
        Comment comment = saveComment();
        long pendingBefore = reportRepository.countByStatus(ReportStatus.PENDING);
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post(REPORT).header("Authorization", bearer(Role.USER))
                            .contentType("application/json").content(body(comment.getId(), "ABUSE")))
                    .andExpect(status().isCreated());
        }
        assertThat(reportRepository.countByStatus(ReportStatus.PENDING)).isEqualTo(pendingBefore + 2);

        mockMvc.perform(post("/api/admin/comments/%d/hide".formatted(comment.getId()))
                        .header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HIDDEN"));

        assertThat(reportRepository.countByStatus(ReportStatus.PENDING)).isEqualTo(pendingBefore);
    }

    @Test
    @DisplayName("가린 글을 복구하면 다시 보인다. 안 가린 글의 복구는 409")
    void restore() throws Exception {
        Comment comment = saveComment();
        String admin = bearer(Role.ADMIN);

        mockMvc.perform(post("/api/admin/comments/%d/restore".formatted(comment.getId()))
                        .header("Authorization", admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_006"));

        mockMvc.perform(post("/api/admin/comments/%d/hide".formatted(comment.getId()))
                .header("Authorization", admin)).andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/comments/%d/restore".formatted(comment.getId()))
                        .header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VISIBLE"));
    }

    @Test
    @DisplayName("기각하면 글은 그대로이고 신고만 닫힌다. 두 번 기각하면 409 DISCUSSION_009")
    void dismiss() throws Exception {
        Comment comment = saveComment();
        String admin = bearer(Role.ADMIN);
        String created = mockMvc.perform(post(REPORT).header("Authorization", bearer(Role.USER))
                        .contentType("application/json").content(body(comment.getId(), "OFF_TOPIC")))
                .andReturn().getResponse().getContentAsString();
        int at = created.indexOf("\"id\":");
        long reportId = Long.parseLong(created.substring(at + 5, created.indexOf(',', at)).trim());

        mockMvc.perform(post("/api/admin/comment-reports/%d/dismiss".formatted(reportId))
                        .header("Authorization", admin)
                        .contentType("application/json").content("{\"note\":\"문제없는 글\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISMISSED"))
                .andExpect(jsonPath("$.data.adminNote").value("문제없는 글"))
                .andExpect(jsonPath("$.data.commentStatus").value("VISIBLE"));

        mockMvc.perform(post("/api/admin/comment-reports/%d/dismiss".formatted(reportId))
                        .header("Authorization", admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_009"));
    }

    private String body(Long commentId, String reason) {
        return "{\"commentId\":%d,\"reason\":\"%s\"}".formatted(commentId, reason);
    }

    private String bearer(Role role) {
        User user = userRepository.save(User.builder()
                .username("crep" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash(passwordEncoder.encode("password123"))
                .role(role)
                .build());
        return "Bearer " + jwtTokenProvider.createToken(user.getId(), role);
    }

    private Comment saveComment() {
        Problem problem = problemRepository.save(Problem.create(
                TestDomains.NETWORK, Difficulty.BEGINNER, ProblemType.OX,
                "TCP 3-way handshake",
                "TCP 연결은 3번의 패킷 교환으로 시작한다.", "O", "SYN → SYN+ACK → ACK", null));
        discussionRepository.insertIfAbsent(problem.getId());
        Long discussionId = discussionRepository.findIdByProblemIdForShare(problem.getId()).orElseThrow();
        return commentRepository.saveAndFlush(Comment.of(discussionId, null, null, "신고될 글"));
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `.\gradlew.bat test --tests "*CommentReportIntegrationTest" --console=plain`
Expected: 컴파일 실패 — `CommentReportRepository`가 없다

- [ ] **Step 3: 엔티티와 사유를 쓴다**

`src/main/java/project/study/study_project/discussion/domain/CommentReportReason.java`

```java
package project.study.study_project.discussion.domain;

/** 댓글 신고 사유. 문구의 주인은 이 enum 하나다 — 화면은 서버가 준 label을 그대로 쓴다. */
public enum CommentReportReason {
    ABUSE("욕설·비방"),
    SPAM("광고·도배"),
    OFF_TOPIC("문제와 무관한 글"),
    OTHER("그 밖의 문제");

    private final String label;

    CommentReportReason(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
```

`src/main/java/project/study/study_project/discussion/domain/CommentReport.java`

```java
package project.study.study_project.discussion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import project.study.study_project.report.domain.ReportStatus;

import java.time.LocalDateTime;

/**
 * 댓글 신고 한 건 — DB의 {@code comment_report} 테이블(V21).
 *
 * <p>상태는 문제 제보의 {@link ReportStatus}를 그대로 쓴다. 뜻이 같다 — 대기, 인정(가림), 기각.
 */
@Entity
@Table(name = "comment_report")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommentReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "comment_id", nullable = false)
    private Long commentId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CommentReportReason reason;

    @Column(length = 500)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private ReportStatus status;

    @Column(name = "admin_note", length = 500)
    private String adminNote;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    private CommentReport(Long commentId, Long userId, CommentReportReason reason, String detail) {
        this.commentId = commentId;
        this.userId = userId;
        this.reason = reason;
        this.detail = detail;
        this.status = ReportStatus.PENDING;
    }

    public static CommentReport of(Long commentId, Long userId, CommentReportReason reason, String detail) {
        return new CommentReport(commentId, userId, reason, detail);
    }

    public void dismiss(String adminNote) {
        this.status = ReportStatus.DISMISSED;
        this.adminNote = adminNote;
        this.resolvedAt = LocalDateTime.now();
    }

    public boolean isPending() {
        return status == ReportStatus.PENDING;
    }
}
```

- [ ] **Step 4: 저장소를 쓴다**

`src/main/java/project/study/study_project/discussion/repository/CommentReportRepository.java`

```java
package project.study.study_project.discussion.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.study_project.discussion.domain.CommentReport;
import project.study.study_project.report.domain.ReportStatus;

import java.time.LocalDateTime;

public interface CommentReportRepository extends JpaRepository<CommentReport, Long> {

    boolean existsByCommentIdAndUserId(Long commentId, Long userId);

    long countByStatus(ReportStatus status);

    /** 대기 목록 — 오래 기다린 것부터. 방치된 신고가 맨 위에 온다(문제 제보함과 같은 규칙). */
    @Query("select r from CommentReport r where (:status is null or r.status = :status) order by r.createdAt asc")
    Page<CommentReport> findOldestFirst(@Param("status") ReportStatus status, Pageable pageable);

    /** 처리된 목록 — 최근 것부터. */
    @Query("select r from CommentReport r where (:status is null or r.status = :status) order by r.createdAt desc")
    Page<CommentReport> findNewestFirst(@Param("status") ReportStatus status, Pageable pageable);

    /**
     * 글을 가릴 때 그 글의 대기 신고를 한 번에 인정으로 바꾼다.
     *
     * <p>벌크 연산은 영속성 컨텍스트를 건너뛴다. 같은 트랜잭션에서 이 신고들을 이미 읽어 둔 코드가
     * 있으면 옛 상태가 보이므로, 부르는 쪽은 이 뒤에 신고를 다시 읽지 않는다.
     */
    @Modifying
    @Query("""
            update CommentReport r set r.status = :accepted, r.resolvedAt = :now
            where r.commentId = :commentId and r.status = :pending
            """)
    int acceptPendingOf(@Param("commentId") Long commentId,
                        @Param("pending") ReportStatus pending,
                        @Param("accepted") ReportStatus accepted,
                        @Param("now") LocalDateTime now);
}
```

- [ ] **Step 5: DTO를 쓴다**

`src/main/java/project/study/study_project/discussion/dto/CommentReportRequest.java`

```java
package project.study.study_project.discussion.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import project.study.study_project.discussion.domain.CommentReportReason;

public record CommentReportRequest(
        @NotNull(message = "신고할 댓글을 지정해 주세요.")
        Long commentId,
        @NotNull(message = "신고 사유를 골라 주세요.")
        CommentReportReason reason,
        @Size(max = 500, message = "상세 내용은 500자를 넘을 수 없습니다.")
        String detail
) {
}
```

`src/main/java/project/study/study_project/discussion/dto/CommentReportItem.java`

```java
package project.study.study_project.discussion.dto;

import project.study.study_project.discussion.domain.Comment;
import project.study.study_project.discussion.domain.CommentReport;
import project.study.study_project.discussion.domain.CommentReportReason;
import project.study.study_project.discussion.domain.CommentStatus;
import project.study.study_project.report.domain.ReportStatus;

import java.time.LocalDateTime;

/**
 * 신고함 한 줄 — 관리자 화면이 읽는 모양.
 *
 * <p>가려진 글의 본문도 싣는다. 학습자 응답과 달리 관리자는 "무엇을 가렸는지" 봐야 복구할지 정한다.
 * 신고자는 싣지 않는다 — 누가 냈는지가 보이면 판단이 내용이 아니라 사람에 끌린다.
 *
 * @param commentNickname 글쓴이 닉네임. 탈퇴했으면 {@code null}
 * @param problemId       그 글이 달린 문제. 문제가 지워지면 신고도 함께 지워지므로 늘 값이 있다
 */
public record CommentReportItem(
        Long id,
        Long commentId,
        String commentBody,
        CommentStatus commentStatus,
        String commentNickname,
        Long problemId,
        String problemTitle,
        CommentReportReason reason,
        String reasonLabel,
        String detail,
        ReportStatus status,
        String adminNote,
        LocalDateTime createdAt,
        LocalDateTime resolvedAt
) {

    public static CommentReportItem of(CommentReport report, Comment comment, String commentNickname,
                                       Long problemId, String problemTitle) {
        return new CommentReportItem(
                report.getId(), comment.getId(), comment.getBody(), comment.getStatus(), commentNickname,
                problemId, problemTitle,
                report.getReason(), report.getReason().getLabel(), report.getDetail(),
                report.getStatus(), report.getAdminNote(), report.getCreatedAt(), report.getResolvedAt());
    }
}
```

- [ ] **Step 6: 서비스를 쓴다**

`src/main/java/project/study/study_project/discussion/service/CommentReportService.java`

```java
package project.study.study_project.discussion.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.discussion.domain.Comment;
import project.study.study_project.discussion.domain.CommentReport;
import project.study.study_project.discussion.domain.CommentStatus;
import project.study.study_project.discussion.domain.Discussion;
import project.study.study_project.discussion.dto.CommentReportItem;
import project.study.study_project.discussion.dto.CommentReportRequest;
import project.study.study_project.discussion.repository.CommentReportRepository;
import project.study.study_project.discussion.repository.CommentRepository;
import project.study.study_project.discussion.repository.DiscussionRepository;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.report.domain.ReportStatus;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.time.LocalDateTime;

/**
 * 댓글 신고 — 접수(학습자)와 판정(관리자).
 *
 * <p>가림은 관리자만 한다(2026-10-03 사용자 결정). 신고가 쌓여도 글은 저절로 가려지지 않는다 —
 * 여럿이 짜고 멀쩡한 글을 내리는 길을 열지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentReportService {

    private final CommentReportRepository reportRepository;
    private final CommentRepository commentRepository;
    private final DiscussionRepository discussionRepository;
    private final ProblemRepository problemRepository;
    private final UserRepository userRepository;

    /* ── 학습자 ───────────────────────────────────────────── */

    /** 중복은 두 겹으로 막는다 — 미리 세어 안내하고, 끼어든 요청은 유일 제약이 막는다(문제 제보와 같은 방식). */
    @Transactional
    public CommentReportItem report(Long userId, CommentReportRequest request) {
        Comment comment = requireComment(request.commentId());
        if (!comment.isVisible()) {
            throw new BusinessException(ErrorCode.DISCUSSION_006);
        }
        if (reportRepository.existsByCommentIdAndUserId(comment.getId(), userId)) {
            throw new BusinessException(ErrorCode.DISCUSSION_007);
        }
        String detail = (request.detail() == null || request.detail().isBlank()) ? null : request.detail().trim();
        try {
            CommentReport saved = reportRepository.saveAndFlush(
                    CommentReport.of(comment.getId(), userId, request.reason(), detail));
            log.info("댓글 신고 접수: commentId={} reason={}", comment.getId(), request.reason());
            return toItem(saved, comment);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DISCUSSION_007);
        }
    }

    /* ── 관리자 ───────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public PageResponse<CommentReportItem> getReports(ReportStatus status, Pageable pageable) {
        Page<CommentReport> page = status == ReportStatus.PENDING
                ? reportRepository.findOldestFirst(status, pageable)
                : reportRepository.findNewestFirst(status, pageable);
        return PageResponse.from(page.map(r -> toItem(r, requireComment(r.getCommentId()))));
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return reportRepository.countByStatus(ReportStatus.PENDING);
    }

    /** 가림 — 그 글에 걸린 대기 신고를 모두 인정으로 닫는다. 같은 글의 신고를 하나씩 누르게 하지 않는다. */
    @Transactional
    public CommentStatus hide(Long commentId) {
        Comment comment = requireComment(commentId);
        if (comment.getStatus() == CommentStatus.DELETED) {
            throw new BusinessException(ErrorCode.DISCUSSION_006);
        }
        comment.hide();
        int accepted = reportRepository.acceptPendingOf(
                commentId, ReportStatus.PENDING, ReportStatus.ACCEPTED, LocalDateTime.now());
        log.info("댓글 가림: commentId={} 닫힌 신고={}", commentId, accepted);
        return comment.getStatus();
    }

    @Transactional
    public CommentStatus restore(Long commentId) {
        Comment comment = requireComment(commentId);
        if (comment.getStatus() != CommentStatus.HIDDEN) {
            throw new BusinessException(ErrorCode.DISCUSSION_006);
        }
        comment.restore();
        log.info("댓글 복구: commentId={}", commentId);
        return comment.getStatus();
    }

    /** 존재를 먼저 보고 상태를 본다 — 뒤집으면 없는 id에 "이미 처리됨"이 나간다. */
    @Transactional
    public CommentReportItem dismiss(Long reportId, String adminNote) {
        CommentReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DISCUSSION_008));
        if (!report.isPending()) {
            throw new BusinessException(ErrorCode.DISCUSSION_009);
        }
        report.dismiss((adminNote == null || adminNote.isBlank()) ? null : adminNote.trim());
        log.info("댓글 신고 기각: reportId={}", reportId);
        return toItem(report, requireComment(report.getCommentId()));
    }

    /* ── 내부 ─────────────────────────────────────────────── */

    private Comment requireComment(Long commentId) {
        return commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DISCUSSION_001));
    }

    /** 신고 한 줄에 글쓴이 닉네임과 문제 제목을 붙인다. 한 쪽이 20건이라 건마다 읽어도 부담이 없다. */
    private CommentReportItem toItem(CommentReport report, Comment comment) {
        String nickname = comment.getUserId() == null ? null
                : userRepository.findById(comment.getUserId()).map(User::getNickname).orElse(null);
        Long problemId = discussionRepository.findById(comment.getDiscussionId())
                .map(Discussion::getProblemId).orElse(null);
        String problemTitle = problemId == null ? null
                : problemRepository.findById(problemId).map(Problem::getTitle).orElse(null);
        return CommentReportItem.of(report, comment, nickname, problemId, problemTitle);
    }
}
```

- [ ] **Step 7: 컨트롤러를 쓴다**

`src/main/java/project/study/study_project/discussion/controller/MyCommentReportController.java`

```java
package project.study.study_project.discussion.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.discussion.dto.CommentReportItem;
import project.study.study_project.discussion.dto.CommentReportRequest;
import project.study.study_project.discussion.service.CommentReportService;
import project.study.study_project.global.response.ApiResponse;

/** 댓글 신고 접수 — 로그인 필수({@code /api/me/**}). 문제를 풀지 않아도 신고할 수 있다. */
@RestController
@RequestMapping("/api/me/comment-reports")
@RequiredArgsConstructor
public class MyCommentReportController {

    private final CommentReportService commentReportService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CommentReportItem> report(@AuthenticationPrincipal Long userId,
                                                 @Valid @RequestBody CommentReportRequest request) {
        return ApiResponse.ok(commentReportService.report(userId, request));
    }
}
```

`src/main/java/project/study/study_project/admin/controller/AdminCommentController.java`

```java
package project.study.study_project.admin.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.discussion.domain.CommentStatus;
import project.study.study_project.discussion.dto.CommentReportItem;
import project.study.study_project.discussion.service.CommentReportService;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.report.domain.ReportStatus;

import java.util.Map;

/**
 * 댓글 신고함과 가림 — {@code /api/admin/**}이라 SecurityConfig의 {@code hasRole(ADMIN)}이 일괄 적용된다.
 *
 * <p>가림·복구·기각이 POST + 동사 경로인 것은 문제 제보함과 같은 판단이다 — 수정이 아니라 판정이다.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminCommentController {

    private final CommentReportService commentReportService;

    @GetMapping("/comment-reports")
    public ApiResponse<PageResponse<CommentReportItem>> list(
            @RequestParam(required = false) ReportStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(commentReportService.getReports(status, pageable));
    }

    /** 대기 건수 — 관리 콘솔 메뉴의 배지. */
    @GetMapping("/comment-reports/pending-count")
    public ApiResponse<Map<String, Long>> pendingCount() {
        return ApiResponse.ok(Map.of("count", commentReportService.pendingCount()));
    }

    @PostMapping("/comment-reports/{id}/dismiss")
    public ApiResponse<CommentReportItem> dismiss(@PathVariable Long id,
                                                  @RequestBody(required = false) Map<String, String> body) {
        return ApiResponse.ok(commentReportService.dismiss(id, body != null ? body.get("note") : null));
    }

    @PostMapping("/comments/{id}/hide")
    public ApiResponse<Map<String, CommentStatus>> hide(@PathVariable Long id) {
        return ApiResponse.ok(Map.of("status", commentReportService.hide(id)));
    }

    @PostMapping("/comments/{id}/restore")
    public ApiResponse<Map<String, CommentStatus>> restore(@PathVariable Long id) {
        return ApiResponse.ok(Map.of("status", commentReportService.restore(id)));
    }
}
```

- [ ] **Step 8: 통과를 확인한다**

Run: `.\gradlew.bat test --tests "*CommentReportIntegrationTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, 테스트 8개 통과

- [ ] **Step 9: 전체 테스트를 돌린다**

Run: `.\gradlew.bat test --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 10: 커밋한다**

```powershell
git add src/main/java/project/study/study_project/discussion src/main/java/project/study/study_project/admin/controller/AdminCommentController.java src/test/java/project/study/study_project/discussion/CommentReportIntegrationTest.java
git commit -F <메시지 파일>
```

메시지: `feat(discussion): 댓글 신고와 관리자 가림·복구·기각 API를 만든다`

---

### Task 6: 토론 화면

**Files:**
- Create: `src/main/resources/static/js/discussion.js`
- Modify: `src/main/resources/static/css/style.css`
- Modify: `src/main/resources/static/js/player.js`
- Modify: `src/main/resources/static/quiz.html`, `daily.html`, `review.html`, `wrong-answers.html`, `problems.html`

**Interfaces:**
- Consumes: Task 2·3·5의 API. `api(path, options)`(실패 시 `code`·`message`가 든 오류를 던진다), `isLoggedIn()`, `escapeHtml()`, `formatDate()` — 모두 `js/api.js`에 있다
- Produces: `discussionBlock(problemId, open)` — 붙일 자리에 넣을 HTML 문자열을 돌려준다. 동작은 `discussion.js`가 `document`에 걸어 둔 처리기가 맡는다

- [ ] **Step 1: `discussion.js`를 쓴다**

`src/main/resources/static/js/discussion.js`

```js
/* =====================================================================
 * csquiz 문제별 토론 — 화면 한 벌(V21)
 * ---------------------------------------------------------------------
 * [왜 파일을 따로 두나]
 * 쓰는 곳이 둘이다(퀴즈 플레이어, 오답노트). 한쪽에 적고 복사하면 신고나 수정 동작이
 * 언젠가 한쪽만 고쳐진다(report.js와 같은 판단).
 *
 * [사용법]
 *   붙일 자리에 discussionBlock(problemId, open)이 돌려준 HTML을 넣는다. 끝.
 *   open=false면 "토론 N개 보기" 입구만 보이고, 누르면 펼쳐진다 — 문제를 풀기 전 자리에 쓴다.
 *   불러오기와 동작은 이 파일이 맡는다. 호출부가 따로 부를 것이 없다.
 * ===================================================================== */

/** 값은 서버의 CommentReportReason enum과 1:1이다. */
const COMMENT_REPORT_REASONS = [
  ["ABUSE", "욕설·비방"],
  ["SPAM", "광고·도배"],
  ["OFF_TOPIC", "문제와 무관한 글"],
  ["OTHER", "그 밖의 문제"],
];

function discussionBlock(problemId, open) {
  return `<section class="discussion" data-discussion="${problemId}" data-open="${open ? 1 : 0}"></section>`;
}

/* 새로 그려진 블록을 찾아 불러온다. 화면이 블록을 넣을 때마다 따로 부르게 하면 언젠가
 * 빠뜨린 화면에서 토론이 비어 있게 된다 — DOM이 바뀌는 것을 지켜보다가 알아서 붙인다. */
function mountDiscussions() {
  document.querySelectorAll("[data-discussion]:not([data-mounted])").forEach(box => {
    box.dataset.mounted = "1";
    loadDiscussion(box, true);
  });
}
new MutationObserver(mountDiscussions).observe(document.documentElement, { childList: true, subtree: true });
document.addEventListener("DOMContentLoaded", mountDiscussions);

/** reset이면 첫 쪽부터 다시, 아니면 다음 쪽을 이어 붙인다. 상태는 블록이 들고 있다(box._d). */
async function loadDiscussion(box, reset) {
  const page = reset ? 0 : Number(box.dataset.page || 0) + 1;
  try {
    const data = await api(`/api/quiz/${box.dataset.discussion}/comments?page=${page}`);
    if (!reset && box._d) data.comments = box._d.comments.concat(data.comments);
    box._d = data;
    box.dataset.page = String(page);
    renderDiscussion(box);
  } catch (e) {
    box.innerHTML = `<div class="disc-msg error">${escapeHtml(e.message)}</div>`;
  }
}

function renderDiscussion(box) {
  const d = box._d;
  if (box.dataset.open !== "1") {
    // 풀기 전 자리. 글이 없으면 입구도 내지 않는다 — 빈 토론을 열어 볼 이유가 없다.
    box.innerHTML = d.total === 0 ? "" : `
      <button type="button" class="disc-peek" data-disc-open>
        💬 토론 ${d.total}개 보기 <span class="meta">정답 이야기가 있을 수 있습니다</span>
      </button>`;
    return;
  }
  box.innerHTML = `
    <h3 class="disc-head">토론 <span class="meta">${d.total}</span></h3>
    <div class="disc-list">${d.comments.length
      ? d.comments.map(c => commentHtml(c, d.canWrite, false)).join("")
      : `<div class="meta">아직 글이 없습니다.</div>`}</div>
    ${d.hasNext ? `<button type="button" class="btn-sm btn-outline" data-disc-more>더 보기</button>` : ""}
    ${writeAreaHtml(d)}
    <div class="disc-msg" hidden></div>`;
}

function writeAreaHtml(d) {
  if (!isLoggedIn()) {
    return `<div class="disc-note"><a href="/login.html">로그인</a>하고 문제를 풀면 참여할 수 있습니다.</div>`;
  }
  if (!d.canWrite) return `<div class="disc-note">문제를 풀면 참여할 수 있습니다.</div>`;
  return formHtml("data-disc-form", "", "", "댓글을 남겨 보세요 (1,000자까지)", "등록", false);
}

function formHtml(kind, id, value, placeholder, label, cancellable) {
  return `<form class="disc-form" ${kind} data-id="${id}">
    <textarea maxlength="1000" rows="2" required placeholder="${placeholder}">${escapeHtml(value)}</textarea>
    <div class="disc-actions">
      <button type="submit" class="btn-sm">${label}</button>
      ${cancellable ? `<button type="button" class="btn-sm btn-outline" data-disc-cancel>취소</button>` : ""}
    </div>
  </form>`;
}

function commentHtml(c, canWrite, isReply) {
  const replies = (c.replies || []).map(r => commentHtml(r, canWrite, true)).join("");
  if (c.status !== "VISIBLE") {
    const text = c.status === "HIDDEN" ? "관리자가 가린 댓글입니다" : "삭제된 댓글입니다";
    return `<div class="disc-item ${isReply ? "reply" : ""} gone" data-comment="${c.id}">
      <div class="meta">${text}</div>${replies}</div>`;
  }
  return `<div class="disc-item ${isReply ? "reply" : ""}" data-comment="${c.id}">
    <div class="disc-meta"><b>${escapeHtml(c.nickname || "탈퇴한 사용자")}</b>
      <span class="meta">${formatDate(c.createdAt)}${c.edited ? " · 수정됨" : ""}</span></div>
    <div class="disc-body">${escapeHtml(c.body)}</div>
    <div class="disc-tools">
      ${canWrite ? `<button type="button" data-disc-reply>답글</button>` : ""}
      ${c.mine ? `<button type="button" data-disc-edit>수정</button>
                  <button type="button" data-disc-delete>삭제</button>` : ""}
      ${isLoggedIn() && !c.mine ? `<button type="button" data-disc-report>신고</button>` : ""}
    </div>
    <div class="disc-slot"></div>
    ${replies}
  </div>`;
}

/** 블록이 들고 있는 목록에서 댓글 하나를 찾는다(답글 포함). */
function findComment(box, id) {
  for (const c of box._d.comments) {
    if (c.id === id) return c;
    const reply = (c.replies || []).find(r => r.id === id);
    if (reply) return reply;
  }
  return null;
}

function discMsg(box, text, isError) {
  const el = box.querySelector(".disc-msg");
  if (!el) return;
  el.hidden = !text;
  el.textContent = text || "";
  el.classList.toggle("error", !!isError);
}

/* ── 누르기 ─────────────────────────────────────────────────────────── */
document.addEventListener("click", async e => {
  const box = e.target.closest("[data-discussion]");
  if (!box) return;
  const item = e.target.closest("[data-comment]");
  const slot = item ? item.querySelector(":scope > .disc-slot") : null;
  const id = item ? Number(item.dataset.comment) : null;

  if (e.target.closest("[data-disc-open]")) { box.dataset.open = "1"; renderDiscussion(box); return; }
  if (e.target.closest("[data-disc-more]")) { await loadDiscussion(box, false); return; }
  if (e.target.closest("[data-disc-cancel]")) { e.target.closest(".disc-slot").innerHTML = ""; return; }

  if (e.target.closest("[data-disc-reply]")) {
    slot.innerHTML = formHtml("data-disc-form", id, "", "답글 쓰기", "답글 등록", true);
    slot.querySelector("textarea").focus();
    return;
  }
  if (e.target.closest("[data-disc-edit]")) {
    slot.innerHTML = formHtml("data-disc-edit-form", id, findComment(box, id).body, "", "저장", true);
    slot.querySelector("textarea").focus();
    return;
  }
  if (e.target.closest("[data-disc-report]")) {
    slot.innerHTML = `<form class="disc-form" data-disc-report-form data-id="${id}">
      <select required>
        <option value="">신고 사유를 골라 주세요</option>
        ${COMMENT_REPORT_REASONS.map(([v, l]) => `<option value="${v}">${escapeHtml(l)}</option>`).join("")}
      </select>
      <input type="text" maxlength="500" placeholder="덧붙일 말 (선택)">
      <div class="disc-actions">
        <button type="submit" class="btn-sm">신고 보내기</button>
        <button type="button" class="btn-sm btn-outline" data-disc-cancel>취소</button>
      </div>
    </form>`;
    return;
  }

  // 삭제는 두 번 눌러야 지워진다 — 확인 창은 습관적으로 넘기게 된다(mypage.html 탈퇴와 같은 방식).
  const del = e.target.closest("[data-disc-delete]");
  if (del) {
    if (!del.dataset.armed) {
      del.dataset.armed = "1";
      del.textContent = "정말 삭제";
      setTimeout(() => { delete del.dataset.armed; del.textContent = "삭제"; }, 5000);
      return;
    }
    try {
      await api(`/api/me/comments/${id}`, { method: "DELETE" });
      await loadDiscussion(box, true);
    } catch (err) { discMsg(box, err.message, true); }
  }
});

/* ── 보내기 ─────────────────────────────────────────────────────────── */
document.addEventListener("submit", async e => {
  const form = e.target.closest("[data-discussion] form");
  if (!form) return;
  e.preventDefault();
  const box = form.closest("[data-discussion]");
  const btn = form.querySelector('button[type="submit"]');
  btn.disabled = true;   // 응답 전에 두 번 눌려 같은 글이 둘 올라가지 않게

  try {
    if (form.hasAttribute("data-disc-form")) {
      await postComment(box, form.dataset.id, form.querySelector("textarea").value);
    } else if (form.hasAttribute("data-disc-edit-form")) {
      await api(`/api/me/comments/${form.dataset.id}`, {
        method: "PUT", body: JSON.stringify({ body: form.querySelector("textarea").value }) });
      await loadDiscussion(box, true);
    } else if (form.hasAttribute("data-disc-report-form")) {
      await sendCommentReport(form);
    } else if (form.hasAttribute("data-disc-nick-form")) {
      await api("/api/me/nickname", {
        method: "PUT", body: JSON.stringify({ nickname: form.querySelector("input").value }) });
      const pending = box._pending;
      box._pending = null;
      await postComment(box, pending.parentId, pending.body);
    }
  } catch (err) {
    // 닉네임 폼의 오류는 폼 안에 적는다. 아래 안내 칸에 쓰면 그 칸에 들어 있는 폼이 지워진다.
    const nickError = form.querySelector(".disc-nick-error");
    if (nickError) { nickError.hidden = false; nickError.textContent = err.message; }
    else discMsg(box, err.message, true);
  } finally {
    if (btn.isConnected) btn.disabled = false;
  }
});

/**
 * 댓글 등록. 닉네임이 없으면(DISCUSSION_003) 그 자리에서 닉네임을 받고 같은 글을 다시 보낸다 —
 * 쓴 글을 버리고 다른 화면으로 보내면 돌아와서 다시 써야 한다.
 */
async function postComment(box, parentId, body) {
  try {
    await api("/api/me/comments", { method: "POST", body: JSON.stringify({
      problemId: Number(box.dataset.discussion), parentId: parentId ? Number(parentId) : null, body }) });
    await loadDiscussion(box, true);
  } catch (err) {
    if (err.code !== "DISCUSSION_003") throw err;
    box._pending = { parentId, body };
    const el = box.querySelector(".disc-msg");
    el.hidden = false;
    el.classList.remove("error");
    el.innerHTML = `<form class="disc-form" data-disc-nick-form>
      <label>토론에서 쓸 닉네임을 정해 주세요 (2~12자, 한글·영문·숫자·밑줄)</label>
      <input type="text" required minlength="2" maxlength="12" pattern="[가-힣A-Za-z0-9_]{2,12}">
      <div class="disc-actions"><button type="submit" class="btn-sm">정하고 등록</button></div>
      <div class="disc-nick-error disc-msg error" hidden></div>
    </form>`;
    el.querySelector("input").focus();
  }
}

/** 이미 신고한 글(DISCUSSION_007)은 실패가 아니라 안내다 — 빨갛게 칠하지 않는다. */
async function sendCommentReport(form) {
  const slot = form.closest(".disc-slot");
  try {
    await api("/api/me/comment-reports", { method: "POST", body: JSON.stringify({
      commentId: Number(form.dataset.id),
      reason: form.querySelector("select").value,
      detail: form.querySelector("input").value }) });
    slot.innerHTML = `<div class="meta">신고를 보냈습니다. 확인하겠습니다.</div>`;
  } catch (err) {
    if (err.code !== "DISCUSSION_007") throw err;
    slot.innerHTML = `<div class="meta">${escapeHtml(err.message)}</div>`;
  }
}
```

- [ ] **Step 2: 모양을 넣는다**

`src/main/resources/static/css/style.css` — `.report-done { ... }` 줄 아래에 넣는다.

```css
/* ── 문제별 토론 (js/discussion.js, V21) ─────────────────────────────
 * 채점 결과 아래에 붙는다. 주인공은 여전히 정답과 해설이라 제목과 테두리를 조용하게 둔다. */
.discussion { margin-top: var(--sp-3); }
.discussion:empty { display: none; }
/* 풀기 전의 입구. 제보 입구(.report-open)와 같은 무게다 — 눌러 달라고 조르지 않는다. */
.disc-peek {
  background: none; border: none; padding: 10px 0;
  color: var(--text-2); font-size: .85rem; cursor: pointer; text-align: left;
}
@media (hover: hover) { .disc-peek:hover { background: none; color: var(--text); } }
.disc-head { font-size: 1rem; margin: 0 0 8px; }
.disc-item { padding: 10px 0; border-top: 1px solid var(--border); }
.disc-item.reply { margin-left: 20px; padding-left: 12px; border-top: none; border-left: 2px solid var(--border); }
.disc-item.gone > .meta { font-style: italic; }
.disc-meta { font-size: .85rem; margin-bottom: 4px; }
/* 줄바꿈을 살린다. 본문은 평문이고 escapeHtml로 넣으므로 pre-wrap이 없으면 한 문단으로 뭉친다. */
.disc-body { white-space: pre-wrap; overflow-wrap: anywhere; font-size: .92rem; }
.disc-tools { display: flex; gap: 12px; margin-top: 4px; }
.disc-tools button {
  background: none; border: none; padding: 6px 0;
  color: var(--muted); font-size: .8rem; cursor: pointer;
}
@media (hover: hover) { .disc-tools button:hover { background: none; color: var(--text-2); } }
.disc-form { margin-top: 8px; }
.disc-form textarea, .disc-form input, .disc-form select { width: 100%; font-size: .9rem; margin-bottom: 6px; }
.disc-actions { display: flex; gap: 8px; }
.disc-note { margin-top: 10px; font-size: .85rem; color: var(--text-2); }
.disc-msg { margin-top: 8px; font-size: .85rem; color: var(--text-2); }
.disc-msg.error { color: var(--wrong); }
/* 문제 목록의 댓글 수. 제목 옆에 조용히 붙는다. */
.pl-comments { margin-left: 6px; font-size: .8rem; color: var(--muted); white-space: nowrap; }
```

- [ ] **Step 3: 플레이어에 토론 자리를 넣는다**

`src/main/resources/static/js/player.js` — 세 군데를 고친다.

(1) `render()` 안, 문제 카드를 그리는 템플릿의 끝부분. 아래 줄을 찾아

```js
        ${keyHint(shortcutHint(p.type))}
      </div>`;
```

이렇게 바꾼다.

```js
        ${keyHint(shortcutHint(p.type))}
      </div>
      <!-- 풀기 전에는 접힌 입구만 보인다. 제출하면 showFeedback이 이 자리를 펼친 토론으로 바꾼다. -->
      <div id="discussionSlot">${discussionBlock(p.id, false)}</div>`;
```

(2) `showFeedback()` 안, `// 3) 제출 버튼 → 다음/결과 버튼으로 교체` 주석 바로 위에 넣는다.

```js
    // 토론을 펼친다. 채점 결과 상자 안이 아니라 카드 아래에 둔다 — 상자의 주인공은 정답과 해설이다.
    const discussionSlot = mountEl.querySelector("#discussionSlot");
    if (discussionSlot) discussionSlot.innerHTML = discussionBlock(p.id, true);
```

(3) `keyHandler`의 첫머리. 아래 줄을 찾아

```js
    if (state.finished) return;
```

그 바로 아래에 넣는다.

```js
    // 토론 영역에서 누른 키는 플레이어 것이 아니다. 안 막으면 댓글을 쓰다 Enter를 치는 순간
    // 다음 문제로 넘어가고, 숫자를 치면 보기가 눌린다.
    if (e.target.closest && e.target.closest("[data-discussion]")) return;
```

- [ ] **Step 4: 화면들에 스크립트를 싣는다**

`quiz.html`, `daily.html`, `review.html` — 세 파일 모두 아래 줄을 찾아

```html
<script src="/js/report.js"></script>
```

그 아래에 한 줄을 넣는다(`player.js`보다 앞이어야 한다).

```html
<script src="/js/discussion.js"></script>
```

`wrong-answers.html` — 같은 줄 아래에 같은 한 줄을 넣는다. 그리고 카드 템플릿의 아래 줄을 찾아

```html
            ${reportBlock(w.problemId)}
```

그 아래에 넣는다.

```html
            <!-- 이미 푼 문제라 토론을 바로 펼친다(V21). -->
            ${discussionBlock(w.problemId, true)}
```

- [ ] **Step 5: 문제 목록에 댓글 수를 붙인다**

`src/main/resources/static/problems.html` — `renderList` 안의 제목 칸을 고친다. 아래 줄을 찾아

```html
        : `<span style="color:var(--muted)">(제목 없음 — 관리 화면에서 채울 수 있습니다)</span>`}</span>
```

이렇게 바꾼다(닫는 `</span>` 앞에 댓글 수 자리를 넣는다).

```html
        : `<span style="color:var(--muted)">(제목 없음 — 관리 화면에서 채울 수 있습니다)</span>`}<span
          class="pl-comments" data-comments-of="${p.id}"></span></span>
```

`renderList` 함수의 맨 끝(닫는 `}` 바로 위)에 넣는다.

```js
  fillCommentCounts(rows);
```

`renderList` 함수 아래에 새 함수를 넣는다.

```js
/**
 * 문제별 댓글 수를 뒤늦게 채운다(V21). 목록 조회에 얹지 않고 따로 묻는 이유: 개수는 목록이
 * 뜨는 데 필요하지 않다. 이 요청이 늦거나 실패해도 목록은 그대로 보인다.
 */
async function fillCommentCounts(rows) {
  if (rows.length === 0) return;
  try {
    const counts = await api("/api/quiz/comment-counts?problemIds=" + rows.map(p => p.id).join(","));
    Object.entries(counts).forEach(([id, n]) => {
      const el = document.querySelector(`[data-comments-of="${id}"]`);
      if (el) el.textContent = `💬 ${n}`;
    });
  } catch (e) { /* 개수는 없어도 되는 정보다 — 조용히 넘긴다 */ }
}
```

- [ ] **Step 6: 브라우저로 확인한다**

앱을 띄운다(`.claude/skills/verify`). 계정 둘을 쓴다 — A(닉네임 없음), B(닉네임 있음).

1. A로 로그인해 `/quiz.html`에서 문제를 연다. 토론이 없는 문제라 문제 아래에 아무것도 없다.
2. 답을 제출한다. 카드 아래에 "토론 0"과 쓰기 칸이 보인다.
3. `<img src=x onerror=alert(1)>`을 쓰고 등록한다. 닉네임 입력이 뜬다. `verify_a`를 넣고 "정하고 등록"을 누른다. 글이 올라가고, **꺾쇠가 글자 그대로 보이며 경고 창이 뜨지 않는다.**
4. 댓글 칸에 `1`을 치고 Enter를 친다. **보기가 눌리거나 다음 문제로 넘어가지 않는다.**
5. 내 글의 "수정"을 눌러 고친다. "수정됨"이 붙는다.
6. B로 로그인해 같은 문제를 `/quiz.html?problemId=<번호>`로 연다. 풀기 전에 "💬 토론 1개 보기"가 보인다. 누르면 펼쳐지고 쓰기 칸 자리에 "문제를 풀면 참여할 수 있습니다"가 있다.
7. B가 문제를 풀고 A의 글에 답글을 단다. 답글이 원글 아래에 들여쓰기로 붙는다.
8. B가 A의 글에서 "신고"를 눌러 사유를 고르고 보낸다. "신고를 보냈습니다."가 뜬다. 한 번 더 신고하면 "이미 신고한 댓글입니다."가 뜬다.
9. A로 돌아와 원글의 "삭제"를 두 번 누른다. "삭제된 댓글입니다" 자리와 B의 답글이 남는다.
10. 로그아웃하고 `/quiz.html`에서 같은 문제를 풀어 본다(비로그인 채점). 토론이 보이고 쓰기 칸 자리에 로그인 안내가 있다.
11. A로 `/problems.html`을 연다. 그 문제 제목 옆에 `💬 1`이 보인다.
12. `/wrong-answers.html`에 틀린 문제가 있으면 카드 아래에 토론이 펼쳐져 있다.
13. 브라우저 콘솔에 오류가 없다.

- [ ] **Step 7: 커밋한다**

```powershell
git add src/main/resources/static/js/discussion.js src/main/resources/static/js/player.js src/main/resources/static/css/style.css src/main/resources/static/quiz.html src/main/resources/static/daily.html src/main/resources/static/review.html src/main/resources/static/wrong-answers.html src/main/resources/static/problems.html
git commit -F <메시지 파일>
```

메시지: `feat(discussion): 플레이어·오답노트에 토론을 붙이고 문제 목록에 댓글 수를 보여 준다`

---

### Task 7: 관리 콘솔 신고함

**Files:**
- Create: `src/main/resources/static/admin/comments.html`
- Modify: `src/main/resources/static/admin/js/admin-shell.js`

**Interfaces:**
- Consumes: Task 5의 관리자 API. `initAdminPage(key)`, `showError(el, e)`(`admin/js/admin-common.js`), `renderAdminPager(data)`(`admin/js/admin-table.js`), `refreshAdminBadges()`(`admin/js/admin-shell.js`)
- Produces: 관리 콘솔 메뉴 "댓글 신고"와 대기 건수 배지

- [ ] **Step 1: 메뉴와 배지를 더한다**

`src/main/resources/static/admin/js/admin-shell.js` — 메뉴 정의에서 아래 줄을 찾아

```js
    { key: "reports",   label: "제보",      href: "/admin/reports.html",   icon: "🚩" },
```

그 아래에 넣는다.

```js
    // 제보 옆인 이유: 둘 다 학습자가 올린 것을 읽고 판정하는 화면이다. 대상이 문제냐 댓글이냐만 다르다.
    { key: "comments",  label: "댓글 신고", href: "/admin/comments.html",  icon: "💬" },
```

`refreshAdminBadges` 안의 `Promise.all`을 고친다. 아래를

```js
  const [problems, documents, topics, reports] = await Promise.all([
    countOf("/api/admin/llm-problems/pending-count"),
    countOf("/api/admin/llm-documents/pending-count"),
    countOf("/api/admin/topic-queue/count"),
    countOf("/api/admin/reports/pending-count"),
  ]);
```

이렇게 바꾼다.

```js
  const [problems, documents, topics, reports, commentReports] = await Promise.all([
    countOf("/api/admin/llm-problems/pending-count"),
    countOf("/api/admin/llm-documents/pending-count"),
    countOf("/api/admin/topic-queue/count"),
    countOf("/api/admin/reports/pending-count"),
    countOf("/api/admin/comment-reports/pending-count"),
  ]);
```

`setBadge("reports", reports, reports > 0);` 아래에 넣는다.

```js
  setBadge("comments", commentReports, commentReports > 0);
```

`detail: { problems, documents, topics, reports },`를 아래로 바꾼다.

```js
    detail: { problems, documents, topics, reports, commentReports },
```

- [ ] **Step 2: 신고함 화면을 쓴다**

`src/main/resources/static/admin/comments.html`

```html
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>관리자 · 댓글 신고 — csquiz</title>
<script>
/* 저장된 테마를 CSS가 오기 전에 <html>에 붙인다 — FOUC 방지. */
try {
  var t = localStorage.getItem("csquiz_theme");
  if (t === "dark" || t === "light") document.documentElement.dataset.theme = t;
} catch (e) {}
</script>
<link rel="stylesheet" href="/css/style.css">
</head>
<body class="admin-shell">
<div id="shell"></div>

<main class="container admin">
  <h1>댓글 신고</h1>
  <div id="guard"></div>

  <div id="adminUi" hidden>
    <!-- 이 화면의 두 버튼이 하는 일이 다르다는 것을 맨 위에 적는다. 가림은 글에, 기각은 신고에 한다. -->
    <div class="alert info" style="margin-bottom:16px">
      학습자가 신고한 토론 댓글입니다. <b>가리기</b>는 그 글을 "관리자가 가린 댓글입니다"로 바꾸고
      같은 글의 다른 신고도 함께 닫습니다. <b>기각</b>은 글을 그대로 두고 이 신고만 닫습니다.
      신고가 쌓여도 글이 저절로 가려지지는 않습니다.
    </div>

    <div class="tabs" id="statusTabs">
      <button class="tab-btn active" data-status="PENDING">대기</button>
      <button class="tab-btn" data-status="ACCEPTED">가림</button>
      <button class="tab-btn" data-status="DISMISSED">기각</button>
      <button class="tab-btn" data-status="">전체</button>
    </div>

    <div id="cMessage"></div>
    <div id="cList"></div>
    <div class="pager-row" id="cPagerRow"></div>
  </div>
</main>

<script src="/js/api.js"></script>
<script src="/js/shell.js"></script>
<script src="/admin/js/admin-shell.js"></script>
<script src="/admin/js/admin-table.js"></script>
<script src="/admin/js/admin-common.js"></script>
<script>
/* 댓글 신고함 — V21. 제보함(reports.html)과 같은 뼈대다: 상태 탭, 카드 목록, 그 자리에서 판정. */
let status = "PENDING";
let page = 0;

if (initAdminPage("comments")) {
  document.getElementById("statusTabs").addEventListener("click", e => {
    const btn = e.target.closest("[data-status]");
    if (!btn) return;
    document.querySelectorAll("#statusTabs .tab-btn").forEach(b => b.classList.remove("active"));
    btn.classList.add("active");
    status = btn.dataset.status;
    page = 0;
    load();
  });
  load();
}

async function load() {
  const msg = document.getElementById("cMessage");
  msg.innerHTML = "";
  try {
    const params = new URLSearchParams({ page });
    if (status) params.set("status", status);
    render(await api("/api/admin/comment-reports?" + params));
  } catch (e) {
    showError(msg, e);
  }
}

function render(data) {
  document.getElementById("cList").innerHTML = data.content.length === 0
    ? `<div class="card">${status === "PENDING" ? "대기 중인 신고가 없습니다. 👍" : "해당하는 신고가 없습니다."}</div>`
    : data.content.map(card).join("");

  const row = document.getElementById("cPagerRow");
  row.innerHTML = `
    <span class="meta">전체 ${data.totalElements}건</span>
    <span class="spacer"></span>
    ${renderAdminPager(data)}`;
  row.querySelectorAll("[data-page]").forEach(btn => {
    btn.addEventListener("click", () => { page = Number(btn.dataset.page); load(); });
  });
}

/**
 * 신고 한 장. 글 본문을 전문으로 싣는다 — "가릴 글인가"를 여기서 판단해야 한다.
 * 신고자는 보여 주지 않는다(서버가 안 내려준다).
 */
function card(r) {
  const done = r.status !== "PENDING";
  return `
    <div class="card ${done ? "draft-done" : ""}" id="creport-${r.id}">
      <div style="margin-bottom:6px">
        <span class="badge ${statusClass(r.status)}">${statusLabel(r.status)}</span>
        <span class="badge">${escapeHtml(r.reasonLabel)}</span>
        <span class="badge gray">글 상태: ${commentStatusLabel(r.commentStatus)}</span>
        <span class="meta">${formatDate(r.createdAt)}</span>
      </div>

      <div class="meta">문제: ${escapeHtml(r.problemTitle || "(제목 없음)")}
        · 글쓴이: ${escapeHtml(r.commentNickname || "탈퇴한 사용자")}</div>
      <div style="white-space:pre-wrap; overflow-wrap:anywhere; margin:8px 0">${escapeHtml(r.commentBody)}</div>

      ${r.detail ? `<div class="alert info" style="margin:8px 0">신고자 한마디: ${escapeHtml(r.detail)}</div>` : ""}
      ${r.adminNote ? `<div class="meta">처리 메모: ${escapeHtml(r.adminNote)}</div>` : ""}

      ${done ? "" : `<input id="cnote-${r.id}" type="text" maxlength="500"
             placeholder="기각 메모 (선택) — '왜 문제없다고 봤나'를 남겨 두세요" style="margin-top:10px">`}

      <div class="row-actions" style="margin-top:10px">
        <a class="btn btn-outline" href="/quiz.html?problemId=${r.problemId}"
           target="_blank" rel="noopener">문제 보기 ↗</a>
        ${done ? "" : `
          <button onclick="hideComment(${r.id}, ${r.commentId}, this)">가리기</button>
          <button class="btn-outline" onclick="dismissReport(${r.id}, this)">기각</button>`}
        ${r.commentStatus === "HIDDEN"
          ? `<button class="btn-outline" onclick="restoreComment(${r.commentId}, this)">글 복구</button>` : ""}
      </div>
    </div>`;
}

function statusLabel(s) { return s === "PENDING" ? "대기" : s === "ACCEPTED" ? "가림" : "기각"; }
function statusClass(s) { return s === "PENDING" ? "orange" : s === "ACCEPTED" ? "green" : "gray"; }
function commentStatusLabel(s) { return s === "VISIBLE" ? "보임" : s === "HIDDEN" ? "가려짐" : "삭제됨"; }

/* 가리기와 복구는 목록을 다시 읽는다. 같은 글의 다른 신고 카드도 함께 바뀌기 때문이다. */
async function hideComment(reportId, commentId, btn) {
  await act(btn, () => api(`/api/admin/comments/${commentId}/hide`, { method: "POST" }), true);
}

async function restoreComment(commentId, btn) {
  await act(btn, () => api(`/api/admin/comments/${commentId}/restore`, { method: "POST" }), true);
}

/* 기각은 그 카드만 바꾼다 — 여러 건을 훑는 화면에서 매번 다시 그리면 읽던 자리를 잃는다. */
async function dismissReport(reportId, btn) {
  const note = document.getElementById("cnote-" + reportId)?.value ?? "";
  await act(btn, async () => {
    const updated = await api(`/api/admin/comment-reports/${reportId}/dismiss`, {
      method: "POST", body: JSON.stringify({ note }) });
    document.getElementById("creport-" + reportId).outerHTML = card(updated);
  }, false);
}

async function act(btn, fn, reload) {
  btn.disabled = true;
  try {
    await fn();
    if (reload) await load();
    refreshAdminBadges();
  } catch (e) {
    btn.disabled = false;
    showError(document.getElementById("cMessage"), e);
  }
}
</script>
</body>
</html>
```

- [ ] **Step 3: 브라우저로 확인한다**

Task 6의 확인에서 B가 보낸 신고가 남아 있다. 없으면 댓글 하나를 쓰고 다른 계정으로 신고한다.

1. 관리자(`admin`)로 로그인해 관리 콘솔을 연다. 왼쪽 "댓글 신고" 옆에 대기 건수가 떠 있다.
2. "댓글 신고"를 연다. 대기 탭에 신고 카드가 있고, 글 본문·사유·문제 제목이 보인다.
3. "가리기"를 누른다. 카드가 대기 탭에서 사라지고 배지 숫자가 줄어든다.
4. "가림" 탭을 연다. 그 카드가 있고 "글 상태: 가려짐"과 "글 복구" 버튼이 보인다.
5. 학습자 화면에서 그 문제의 토론을 연다. "관리자가 가린 댓글입니다"로 보인다.
6. 관리 콘솔에서 "글 복구"를 누른다. 학습자 화면을 새로 고치면 글이 다시 보인다.
7. 새 신고를 하나 더 만들어 "기각"을 누른다. 그 카드만 "기각"으로 바뀌고 글은 그대로다.
8. 일반 사용자로 `/admin/comments.html`을 열면 화면이 뜨지 않는다(404).

- [ ] **Step 4: 커밋한다**

```powershell
git add src/main/resources/static/admin/comments.html src/main/resources/static/admin/js/admin-shell.js
git commit -F <메시지 파일>
```

메시지: `feat(admin): 댓글 신고함을 만들고 메뉴에 대기 건수를 띄운다`

---

### Task 8: 마무리 확인

**Files:**
- 변경 없음(확인만 한다)

- [ ] **Step 1: 전체 테스트를 돌린다**

Run: `.\gradlew.bat test --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: 도배 방지를 실제로 밟아 본다**

요청 제한이 켜진 채(기본값)로 앱을 띄운다. Redis가 떠 있어야 한다. 푼 문제 하나에 댓글을 연달아 6번 등록한다.
Expected: 6번째에 "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."가 토론 아래에 뜬다. 1분 뒤에는 다시 등록된다.

- [ ] **Step 3: 설계 문서와 맞는지 대조한다**

`docs/superpowers/specs/2026-10-03-problem-discussion-design.md`의 3절 API 표, 5절 오류 표를 한 줄씩 읽으며 실제 응답과 맞는지 본다. 어긋난 곳이 있으면 코드를 고친다. 설계를 바꿔야 하면 사용자에게 먼저 묻는다.

- [ ] **Step 4: 사용자에게 보고한다**

변경 파일, 테스트 결과, 남은 일을 표로 보고한다. 남은 일에는 설계 문서 1절의 "공개 전에 따로 다뤄야 할 것"(사용자 차단·정지)을 적는다. main 머지와 push는 사용자에게 묻는다.
