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
     * cascade/orphanRemoval은 걸지 않았다 — MVP는 시드 데이터 읽기 전용이라
     * 엔티티를 통한 보기 추가/삭제 시나리오 자체가 없기 때문(DDL의 ON DELETE CASCADE로 충분).
     */
    @OneToMany(mappedBy = "problem", fetch = FetchType.LAZY)
    @OrderBy("seq ASC")
    private List<Choice> choices = new ArrayList<>();

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
