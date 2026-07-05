package project.study.study_project.document.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.support.PageableExecutionUtils;
import project.study.study_project.document.domain.QDocument;
import project.study.study_project.document.dto.DocumentListItem;
import project.study.study_project.global.common.Domain;
import project.study.study_project.tag.domain.QTag;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Document QueryDSL 구현 — 로드맵 1 · 2부의 본체. 무엇을 개선했나(실측 근거는 docs/08):
 *
 * <p><b>개선 전</b>(Specification + 엔티티 조회 + LAZY 태그):
 * <ul>
 *   <li>쿼리 수가 설정에 좌우됐다 — batch_fetch_size=100이면 2방, 없으면 <b>1+N(실측 10방)</b>.
 *       "설정 하나 지우면 성능 사고"인 잠복 구조.
 *   <li>엔티티를 통째로 읽어 <b>본문(content_md, LONGTEXT)까지 매번 전송</b>했다 —
 *       목록 DTO는 본문을 버리는데도. 문서가 길어질수록 목록 API가 무거워지는 구조.
 * </ul>
 *
 * <p><b>개선 후</b>: 어떤 설정에서도 <b>정확히 2방(+ 필요할 때만 count 1방)</b>으로 고정.
 * <ol>
 *   <li>1방: 필터·정렬·페이징을 걸어 <b>목록에 필요한 컬럼만</b> DTO 프로젝션으로 조회 (본문 제외)
 *   <li>2방: 그 문서 id들의 태그를 IN 한 방으로 모아 메모리에서 붙임
 * </ol>
 *
 * <p>왜 "엔티티 + 컬렉션 fetch join"이 아니라 이 방식인가:
 * 컬렉션 fetch join에 페이징을 걸면 Hibernate가 <b>전체를 메모리로 가져와 자르는</b>(HHH90003004)
 * 함정이 있다 — 조인으로 행이 (문서×태그)만큼 불어나 DB가 offset/limit을 못 자르기 때문.
 * 그걸 피하는 표준 패턴이 "id 페이징 후 IN 조회" 2단계인데, 우리는 어차피 목록이 DTO라서
 * 엔티티를 거치지 않고 처음부터 필요한 컬럼만 뽑는 프로젝션이 더 싸고 단순하다.
 */
@RequiredArgsConstructor
public class DocumentRepositoryImpl implements DocumentRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    /**
     * 1방째 결과를 담는 중간 행 — domainLabel·tags를 붙이기 전의 순수 DB 값.
     * <b>public인 이유</b>: Projections.constructor가 리플렉션으로 생성자를 찾는데,
     * private record는 생성자도 private이라 "No constructor found" 예외가 난다(실제 겪음).
     */
    public record DocRow(Long id, Domain domain, String title, String slug, LocalDateTime updatedAt) {
    }

    @Override
    public Page<DocumentListItem> searchListItems(Domain domain, List<String> tagNames, Pageable pageable) {
        QDocument d = QDocument.document;

        BooleanBuilder where = new BooleanBuilder();
        if (domain != null) {
            where.and(d.domain.eq(domain));
        }
        if (tagNames != null && !tagNames.isEmpty()) {
            // any() → EXISTS 서브쿼리로 번역된다. 태그를 join으로 걸면 태그 수만큼 행이
            // 복제돼 distinct가 필요해지고 페이징 계산도 꼬인다 — EXISTS는 행을 안 불린다.
            where.and(d.tags.any().name.in(tagNames));
        }

        // ① 목록 페이지: 필요한 컬럼만 DTO로 (content_md는 select 자체에 없음)
        List<DocRow> rows = queryFactory
                .select(Projections.constructor(DocRow.class,
                        d.id, d.domain, d.title, d.slug, d.updatedAt))
                .from(d)
                .where(where)
                .orderBy(toOrderSpecifiers(pageable.getSort(), d))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        // ② 이 페이지 문서들의 태그만 IN 한 방 — 문서당 반복 조회(N+1)가 구조적으로 불가능
        Map<Long, List<String>> tagsByDocId = loadTags(rows.stream().map(DocRow::id).toList());

        List<DocumentListItem> content = rows.stream()
                .map(r -> new DocumentListItem(
                        r.id(), r.domain(), r.domain().getDisplayName(), r.title(), r.slug(),
                        tagsByDocId.getOrDefault(r.id(), List.of()), r.updatedAt()))
                .toList();

        // count는 PageableExecutionUtils에 위임 — 첫 페이지가 꽉 차지 않는 등
        // 전체 개수를 셀 필요가 없는 경우 count 쿼리를 아예 생략해 준다.
        return PageableExecutionUtils.getPage(content, pageable, () -> {
            Long total = queryFactory.select(d.count()).from(d).where(where).fetchOne();
            return total == null ? 0 : total;
        });
    }

    /** 문서 id들 → {문서 id: 태그명 목록}. 컬렉션 경로 join이라 조인 테이블(document_tag)을 알아서 탄다. */
    private Map<Long, List<String>> loadTags(List<Long> docIds) {
        if (docIds.isEmpty()) {
            return Map.of();
        }
        QDocument d = QDocument.document;
        QTag t = QTag.tag;
        List<Tuple> tuples = queryFactory
                .select(d.id, t.name)
                .from(d)
                .join(d.tags, t)
                .where(d.id.in(docIds))
                .fetch();
        return tuples.stream().collect(Collectors.groupingBy(
                tuple -> tuple.get(d.id),
                Collectors.mapping(tuple -> tuple.get(t.name), Collectors.toList())));
    }

    /**
     * Pageable의 Sort → QueryDSL OrderSpecifier 변환.
     * 허용 목록(화이트리스트) 방식 — 클라이언트가 보낸 임의 문자열을 그대로 정렬에 쓰면
     * 존재하지 않는 컬럼으로 500이 나거나 의도치 않은 컬럼 정렬이 가능해진다.
     * 마지막에 id를 보조 정렬로 붙여, 같은 값이 많을 때 페이지를 넘길 때마다
     * 행이 겹치거나 빠지는 문제(불안정 정렬)를 막는다.
     */
    private OrderSpecifier<?>[] toOrderSpecifiers(Sort sort, QDocument d) {
        List<OrderSpecifier<?>> orders = new ArrayList<>();
        for (Sort.Order o : sort) {
            OrderSpecifier<?> spec = switch (o.getProperty()) {
                case "createdAt" -> o.isAscending() ? d.createdAt.asc() : d.createdAt.desc();
                case "updatedAt" -> o.isAscending() ? d.updatedAt.asc() : d.updatedAt.desc();
                case "title" -> o.isAscending() ? d.title.asc() : d.title.desc();
                default -> null; // 허용 목록 밖 속성은 조용히 무시
            };
            if (spec != null) {
                orders.add(spec);
            }
        }
        if (orders.isEmpty()) {
            orders.add(d.createdAt.desc()); // 스펙(docs/03)의 기본 정렬
        }
        orders.add(d.id.desc());
        return orders.toArray(OrderSpecifier[]::new);
    }
}
