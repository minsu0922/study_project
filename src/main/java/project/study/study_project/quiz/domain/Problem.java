package project.study.study_project.quiz.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 문제 — DB의 {@code problem} 테이블과 대응(문서 01-data-model).
 *
 * <p>설계 메모:
 * <ul>
 *   <li><b>{@code answer} 컬럼은 타입별 의미가 다르다</b>(문서 01의 규칙):
 *       객관식은 {@code NULL}(정답을 Choice.is_correct로 판정), OX는 {@code "O"/"X"},
 *       단답형은 복수 정답을 {@code |}로 구분(예 {@code "tcp|transmission control protocol"}).
 *       한 컬럼을 타입별로 다르게 쓰는 대신 타입별 테이블로 쪼갤 수도 있었지만,
 *       MVP 3종 채점엔 과설계라 판단해 단일 컬럼 + 규칙 문서화를 택했다.
 *   <li><b>보기(Choice)는 {@code @OneToMany} LAZY</b>. 퀴즈 목록 조회 시 문제마다 보기를 따로 읽는
 *       N+1이 생기지만 {@code default_batch_fetch_size=100}으로 IN 조회로 묶여 완화된다.
 *       본격 최적화(fetch join)는 로드맵 1(QueryDSL)에서 다룬다.
 *   <li>{@code @OrderBy("seq ASC")}로 보기를 항상 화면 표시 순서(1..N)로 가져온다 —
 *       정렬을 DB에 맡겨 서비스/DTO 계층에서 재정렬할 필요를 없앤다.
 *   <li>태그(problem_tag)는 스키마엔 있지만 MVP 퀴즈 API가 태그 필터를 쓰지 않으므로
 *       엔티티 매핑을 아직 만들지 않았다(필요해질 때 추가 — YAGNI).
 *   <li>MVP는 문제를 API로 만들지 않고 시드(SQL)로만 넣는 <b>읽기 전용</b> 엔티티라
 *       수정용 메서드 없이 조회만 제공한다.
 * </ul>
 */
@Entity
@Table(name = "problem")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Problem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Domain domain;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private Difficulty difficulty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProblemType type;

    /**
     * 목록 화면에 뜰 한 줄 제목 — 없으면 {@code null}(V13).
     *
     * <p><b>지문에서 잘라 쓸 수 없어서 생긴 컬럼이다.</b> 목록은 한 행에 제목 한 줄을 보여 주는데,
     * 지문의 앞부분을 자르면 "주문 API에서 처리 건수를 세려고 서비스 클래스에 static 카운터를…"이
     * 나온다 — 무엇을 묻는 문제인지 알 수 없다. 2026-08-18에 중급·고급 지문을 <실무 장면>으로
     * 쓰게 바꾸면서({@code ClaudeProblemGenerator}의 [상황 지문 쓰는 법]) 지문 첫 문장은
     * 이제 상황 서술이지 주제가 아니다. 자르기가 더 안 통하게 됐다.
     *
     * <p><b>{@code null}을 허용하는 이유</b>: 이 컬럼보다 먼저 만들어진 문제들에는 넣을 값이 없다.
     * 마이그레이션에서 지문 앞부분으로 채워 두면 <b>가짜 제목이 진짜인 척</b> 들어앉아 백필 대상을
     * 구분할 수 없어진다. 화면 쪽이 비었을 때 지문으로 대신 보여 주는 편이 정직하다.
     */
    @Column(length = 120)
    private String title;

    /** 문제 지문. MySQL TEXT 컬럼이라 String 기본 매핑(VARCHAR)과 구분하기 위해 LONGVARCHAR 지정. */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false)
    private String question;

    /** 채점 기준값. 타입별 규칙은 클래스 주석 참고(객관식=NULL / OX="O|X" / 단답형="a|b"). */
    @Column(length = 500)
    private String answer;

    /** 해설 — 채점 응답에서만 노출(풀이용 조회에서는 절대 반환 금지, docs/03). */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column
    private String explanation;

    /**
     * 객관식 보기. 객관식이 아닌 문제는 빈 리스트.
     *
     * <p>{@code cascade = ALL, orphanRemoval = true}: 보기는 문제에 완전히 종속된
     * 자식(생명주기가 문제와 같음)이라, 문제를 저장/삭제하면 보기도 따라간다.
     * 수정 시에도 리스트에서 빼기만 하면 orphanRemoval이 DELETE를 날려 줘서
     * 별도 ChoiceRepository 없이 문제 하나만 다루면 된다. (관리자 등록 기능에서 사용)
     */
    @OneToMany(mappedBy = "problem", fetch = FetchType.LAZY,
            cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seq ASC")
    private List<Choice> choices = new ArrayList<>();

    /**
     * 근거가 된 개념 문서의 slug — 없으면 {@code null}(V9).
     *
     * <p>연관관계({@code @ManyToOne Document})가 아니라 문자열인 이유는 V9 주석에 적었다.
     * 요약하면 <b>문서보다 문제가 먼저 존재할 수 있고</b>, 연결이 끊어져도 손해가
     * "링크가 안 뜬다"뿐이라 무결성 제약의 경직성을 살 이유가 없다.
     */
    @Column(name = "document_slug", length = 150)
    private String documentSlug;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private Problem(Domain domain, Difficulty difficulty, ProblemType type, String title,
                    String question, String answer, String explanation, String documentSlug) {
        this.domain = domain;
        this.difficulty = difficulty;
        this.type = type;
        this.title = title;
        this.question = question;
        this.answer = answer;
        this.explanation = explanation;
        this.documentSlug = documentSlug;
    }

    /**
     * 관리자 등록용 팩터리. <b>타입별 규칙 검증(객관식=answer 없음 등)은 서비스가 끝낸 뒤</b>
     * 호출한다 — 엔티티는 저장 형태만 책임지고, 규칙 판단은 한 곳(AdminProblemService)에 모은다.
     *
     * <p><b>{@code title}과 {@code question}이 나란히 있는 것을 주의해서 부른다.</b> 둘 다
     * String이라 순서를 바꿔 넣어도 컴파일된다 — 컬럼 순서(V13)와 필드 순서에 맞춰 title을
     * 앞에 두었으니, 부르는 쪽도 같은 순서로 적어 눈으로 대조할 수 있게 한다.
     */
    public static Problem create(Domain domain, Difficulty difficulty, ProblemType type, String title,
                                 String question, String answer, String explanation,
                                 String documentSlug) {
        return new Problem(domain, difficulty, type, title, question, answer, explanation, documentSlug);
    }

    /** 관리자 수정용 — id/created_at만 남기고 내용 필드를 통째로 교체한다(부분 수정 없음: 폼 전체 제출 방식). */
    public void update(Domain domain, Difficulty difficulty, ProblemType type, String title,
                       String question, String answer, String explanation, String documentSlug) {
        this.domain = domain;
        this.difficulty = difficulty;
        this.type = type;
        this.title = title;
        this.question = question;
        this.answer = answer;
        this.explanation = explanation;
        this.documentSlug = documentSlug;
    }

    /**
     * 보기 전체 교체. 기존 리스트를 비우면 orphanRemoval이 기존 행을 지우고,
     * 새 보기를 추가하면 cascade가 INSERT한다 — "수정 = 전부 지우고 다시 넣기" 전략.
     * 보기별 부분 수정보다 단순하고, 보기 수가 최대 5개 수준이라 성능 손해도 무시할 만하다.
     */
    public void replaceChoices(List<Choice> newChoices) {
        this.choices.clear();
        this.choices.addAll(newChoices);
    }
}
