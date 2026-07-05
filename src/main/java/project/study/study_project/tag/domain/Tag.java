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
 * <p>처음(MVP)엔 시드 데이터로만 넣는 읽기 전용이었으나, 관리자 콘텐츠 등록 기능이 생기면서
 * "없는 태그는 만들어 쓰는" find-or-create 방식이 필요해져 생성 팩터리를 추가했다.
 * 이름은 저장 전에 소문자·trim으로 정규화한다 — "TCP"와 "tcp"가 다른 태그로 갈라지는 것 방지.
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

    private Tag(String name) {
        this.name = name;
    }

    /** 정규화(소문자·trim)된 이름으로 태그 생성. 정규화를 여기서 강제해 우회 생성을 막는다. */
    public static Tag of(String rawName) {
        return new Tag(rawName.trim().toLowerCase());
    }
}
