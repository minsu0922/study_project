package project.study.study_project.dailyquiz.service;

import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import project.study.study_project.dailyquiz.repository.DailyQuizRepository;
import project.study.study_project.user.service.AccountDataCleaner;

/** 탈퇴 때 이 패키지의 기록을 지운다. 차례는 {@link AccountDataCleaner}가 정한다. */
@Component
@Order(AccountDataCleaner.ORDER_DAILY_QUIZ)
@RequiredArgsConstructor
public class DailyQuizAccountCleaner implements AccountDataCleaner {

    private final DailyQuizRepository repository;

    @Override
    public void deleteFor(Long userId) {
        repository.deleteItemsByUserId(userId);   // 손자 먼저
        repository.deleteAllByUserId(userId);
    }
}
