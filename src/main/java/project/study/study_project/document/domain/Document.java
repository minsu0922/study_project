package project.study.study_project.document.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import project.study.study_project.global.common.Domain;
import project.study.study_project.tag.domain.Tag;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * CS 지식 문서 — DB의 {@code document} 테이블과 대응(문서 01-data-model).
 *
 * <p>설계 메모:
 * <ul>
 *   <li><b>태그 연결은 {@code @ManyToMany} + {@code document_tag} 조인 테이블</b>로 매핑한다.
 *       MVP는 문서를 API로 만들지 않고 시드(SQL)로 넣으며 <b>읽기 전용</b>이라, 별도 연결 엔티티
 *       (DocumentTag) 없이 다대다 매핑만으로 충분하다. (태그 편집 기능이 생기면 그때 연결 엔티티로 승격)
 *   <li>연관은 <b>지연(LAZY) 로딩</b>. 목록 조회 시 문서마다 태그를 따로 읽는 N+1이 생기지만,
 *       {@code default_batch_fetch_size=100}으로 완화하고 본격 최적화는 로드맵1(QueryDSL+fetch join)로 미룬다.
 *   <li>본문 {@code content_md}는 MySQL {@code LONGTEXT}. Hibernate에 이를 알려 주려고
 *       {@code @JdbcTypeCode(LONGVARCHAR)}를 붙였다(그냥 String이면 VARCHAR로 취급될 수 있음).
 *   <li>{@code updated_at}은 {@code @LastModifiedDate}로 수정 시 자동 갱신(auditing).
 * </ul>
 */
@Entity
@Table(name = "document")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Domain domain;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, unique = true, length = 150)
    private String slug;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR) // MySQL LONGTEXT에 대응
    @Column(name = "content_md", nullable = false)
    private String contentMd;

    @Column(length = 500)
    private String source;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "document_tag",
            joinColumns = @JoinColumn(name = "document_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private Set<Tag> tags = new LinkedHashSet<>();

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
