package project.study.study_project.review.service;

import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import project.study.study_project.review.repository.ReviewItemRepository;
import project.study.study_project.user.service.AccountDataCleaner;

/** 탈퇴 때 이 패키지의 기록을 지운다. 차례는 {@link AccountDataCleaner}가 정한다. */
@Component
@Order(AccountDataCleaner.ORDER_REVIEW)
@RequiredArgsConstructor
public class ReviewAccountCleaner implements AccountDataCleaner {

    private final ReviewItemRepository repository;

    @Override
    public void deleteFor(Long userId) {
        repository.deleteAllByUserId(userId);
    }
}
