package project.study.study_project.bookmark.dto;

import project.study.study_project.bookmark.domain.Bookmark;
import project.study.study_project.document.domain.Document;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.quiz.domain.Problem;

import java.time.LocalDateTime;

/**
 * 북마크 목록 한 줄.
 *
 * @param targetId   문제 id 또는 문서 id
 * @param title      문제는 제목(없으면 지문), 문서는 문서 제목
 * @param difficulty 문서는 {@code null}
 * @param slug       문제는 {@code null} — 문서 화면 주소가 slug로 열린다
 */
public record BookmarkItem(
        BookmarkTarget type,
        Long targetId,
        String title,
        DomainCode domain,
        Difficulty difficulty,
        String slug,
        LocalDateTime createdAt
) {
    public static BookmarkItem ofProblem(Bookmark b) {
        Problem p = b.getProblem();
        return new BookmarkItem(BookmarkTarget.PROBLEM, p.getId(),
                p.getTitle() != null ? p.getTitle() : p.getQuestion(),
                p.getDomain(), p.getDifficulty(), null, b.getCreatedAt());
    }

    public static BookmarkItem ofDocument(Bookmark b) {
        Document d = b.getDocument();
        return new BookmarkItem(BookmarkTarget.DOCUMENT, d.getId(), d.getTitle(),
                d.getDomain(), null, d.getSlug(), b.getCreatedAt());
    }
}
