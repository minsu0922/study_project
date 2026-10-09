package project.study.study_project.quiz.service;

import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import project.study.study_project.quiz.repository.SubmissionRepository;
import project.study.study_project.user.service.AccountDataCleaner;

/** 탈퇴 때 이 패키지의 기록을 지운다. 차례는 {@link AccountDataCleaner}가 정한다. */
@Component
@Order(AccountDataCleaner.ORDER_SUBMISSION)
@RequiredArgsConstructor
public class SubmissionAccountCleaner implements AccountDataCleaner {

    private final SubmissionRepository repository;

    @Override
    public void deleteFor(Long userId) {
        repository.deleteAllByUserId(userId);
    }
}
