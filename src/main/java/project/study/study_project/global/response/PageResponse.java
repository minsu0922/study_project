package project.study.study_project.global.response;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 목록 응답의 data 규격 — 문서 04-response-format 기준.
 * <p>Spring {@link Page}를 그대로 노출하지 않고 계약이 고정된 형태로 변환한다
 * (Page의 직렬화 포맷은 버전에 따라 바뀌므로 API 계약으로 쓰지 않는다).
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext()
        );
    }
}
