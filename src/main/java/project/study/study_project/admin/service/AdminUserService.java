package project.study.study_project.admin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminSuspendRequest;
import project.study.study_project.admin.dto.AdminUserItem;
import project.study.study_project.global.common.SearchKeyword;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 사용자 찾기와 정지·해제(V26).
 *
 * <p>정지는 쓰기만 막는다. 무엇이 막히는지는 {@code SuspensionGuard}를 부르는 곳이 정한다 —
 * 여기는 "누구를 언제까지"만 적는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    /** 고를 수 있는 정지 일수. 화면의 선택지와 같다 — 아무 숫자나 받으면 기준이 관리자마다 달라진다. */
    private static final Set<Integer> ALLOWED_DAYS = Set.of(1, 7, 30);

    private final UserRepository userRepository;

    /**
     * @param q             아이디나 닉네임의 일부. 비우면 전체
     * @param suspendedOnly 지금 정지 중인 사람만
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminUserItem> search(String q, boolean suspendedOnly, Pageable pageable) {
        LocalDateTime now = LocalDateTime.now();
        String keyword = SearchKeyword.likePattern(q);
        return PageResponse.from(userRepository.search(keyword, suspendedOnly, now, pageable)
                .map(user -> AdminUserItem.of(user, now)));
    }

    @Transactional(readOnly = true)
    public AdminUserItem detail(Long userId) {
        return AdminUserItem.of(requireUser(userId), LocalDateTime.now());
    }

    /** 이미 정지 중이면 새 기간과 사유로 덮어쓴다 — 7일을 30일로 늘릴 때 풀었다가 다시 걸게 하지 않는다. */
    @Transactional
    public AdminUserItem suspend(Long userId, AdminSuspendRequest request) {
        if (request.days() != null && !ALLOWED_DAYS.contains(request.days())) {
            throw new BusinessException(ErrorCode.COMMON_001, "정지 기간은 1일·7일·30일·무기한 가운데 하나입니다.");
        }
        User user = requireUser(userId);
        if (user.getRole() == Role.ADMIN) {
            throw new BusinessException(ErrorCode.USER_002);
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime until = request.days() == null ? User.INDEFINITE : now.plusDays(request.days());
        user.suspend(until, request.reason().trim());
        log.info("사용자 정지: userId={} days={}", userId, request.days());
        return AdminUserItem.of(user, now);
    }

    /** 정지가 아닌 사람을 풀어도 오류가 아니다 — 두 번 눌린 해제 버튼에 실패를 보여 줄 이유가 없다. */
    @Transactional
    public AdminUserItem unsuspend(Long userId) {
        User user = requireUser(userId);
        user.unsuspend();
        log.info("사용자 정지 해제: userId={}", userId);
        return AdminUserItem.of(user, LocalDateTime.now());
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_001));
    }
}
