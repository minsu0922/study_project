package project.study.study_project.bookmark.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.bookmark.dto.BookmarkItem;
import project.study.study_project.bookmark.dto.BookmarkState;
import project.study.study_project.bookmark.dto.BookmarkTarget;
import project.study.study_project.bookmark.repository.BookmarkRepository;
import project.study.study_project.document.domain.Document;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;

import java.time.LocalDateTime;
import java.util.List;

/** 북마크(V33) — 담기·빼기·목록. 담기와 빼기는 몇 번을 불러도 결과가 같다. */
@Service
@RequiredArgsConstructor
public class BookmarkService {

    /** 상태 조회 한 번에 받는 id 수. 한 화면에 보이는 문제 수(20)보다 넉넉하게 잡았다. */
    private static final int STATE_MAX = 100;

    private final BookmarkRepository bookmarkRepository;
    private final ProblemRepository problemRepository;
    private final DocumentRepository documentRepository;

    /** 내려 둔 문제·문서는 없는 것으로 본다 — 학습자 화면에서 보이지 않는 것을 담을 수는 없다. */
    @Transactional
    public void add(Long userId, BookmarkTarget type, Long targetId) {
        LocalDateTime now = LocalDateTime.now();
        if (type == BookmarkTarget.PROBLEM) {
            problemRepository.findById(targetId).filter(p -> !p.isHidden())
                    .map(Problem::getId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_001));
            bookmarkRepository.addProblem(userId, targetId, now);
        } else {
            documentRepository.findById(targetId).filter(d -> !d.isHidden())
                    .map(Document::getId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.DOC_001));
            bookmarkRepository.addDocument(userId, targetId, now);
        }
    }

    /** 담겨 있지 않아도 오류가 아니다 — 두 번 눌린 버튼에 실패를 보여 줄 이유가 없다. */
    @Transactional
    public void remove(Long userId, BookmarkTarget type, Long targetId) {
        if (type == BookmarkTarget.PROBLEM) {
            bookmarkRepository.removeProblem(userId, targetId);
        } else {
            bookmarkRepository.removeDocument(userId, targetId);
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<BookmarkItem> list(Long userId, BookmarkTarget type, Pageable pageable) {
        return type == BookmarkTarget.PROBLEM
                ? PageResponse.from(bookmarkRepository.findProblems(userId, pageable).map(BookmarkItem::ofProblem))
                : PageResponse.from(bookmarkRepository.findDocuments(userId, pageable).map(BookmarkItem::ofDocument));
    }

    /** 빈 목록은 조회하지 않는다 — {@code IN ()}은 SQL 문법 오류다. */
    @Transactional(readOnly = true)
    public BookmarkState state(Long userId, List<Long> problemIds, List<Long> documentIds) {
        return new BookmarkState(
                problemIds.isEmpty() ? List.of()
                        : bookmarkRepository.findBookmarkedProblemIds(userId, capped(problemIds)),
                documentIds.isEmpty() ? List.of()
                        : bookmarkRepository.findBookmarkedDocumentIds(userId, capped(documentIds)));
    }

    private List<Long> capped(List<Long> ids) {
        return ids.size() > STATE_MAX ? ids.subList(0, STATE_MAX) : ids;
    }
}
