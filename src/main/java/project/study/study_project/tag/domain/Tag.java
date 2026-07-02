package project.study.study_project.tag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 태그 — 문서·문제에 공통으로 붙는 꼬리표(문서 01-data-model).
 *
 * <p>MVP에서는 태그를 API로 생성하지 않고 시드 데이터(SQL)로 넣는다. 그래서 이 엔티티는
 * 주로 <b>읽기</b>에 쓰이며, 별도 생성 로직/빌더를 두지 않았다.
 */
@Entity
@Table(name = "tag")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;
}
