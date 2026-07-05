package project.study.study_project.tag.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.study.study_project.tag.domain.Tag;
import project.study.study_project.tag.repository.TagRepository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 태그 find-or-create — 관리자 콘텐츠 등록에서 "tcp, http"처럼 이름만 넘기면
 * 있는 태그는 재사용하고 없는 태그는 만들어 돌려준다.
 *
 * <p>별도 서비스로 뺀 이유: 문제 등록과 문서 등록이 <b>같은 규칙</b>(정규화·재사용)을 써야 한다.
 * 각자 구현하면 한쪽만 소문자 정규화를 빼먹는 식의 불일치가 생기기 쉽다.
 */
@Service
@RequiredArgsConstructor
public class TagService {

    private final TagRepository tagRepository;

    /**
     * 이름 목록 → Tag 엔티티 집합. 호출자의 트랜잭션 안에서 실행된다(@Transactional 없음 — 참여만).
     *
     * <p>정규화(trim+소문자)는 Tag.of()가 강제한다. 빈 문자열·중복 이름은 걸러진다.
     * 동시에 같은 새 태그를 만들면 UNIQUE 제약(uk_tag_name)이 한쪽을 실패시키는데,
     * 관리자 1명이 쓰는 MVP에선 재시도 로직까지는 과설계라 두지 않았다.
     */
    public Set<Tag> resolveTags(List<String> names) {
        Set<Tag> result = new LinkedHashSet<>();
        if (names == null) {
            return result;
        }
        for (String raw : names) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String normalized = raw.trim().toLowerCase();
            Tag tag = tagRepository.findByName(normalized)
                    .orElseGet(() -> tagRepository.save(Tag.of(normalized)));
            result.add(tag);
        }
        return result;
    }
}
