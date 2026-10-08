package project.study.study_project.admin.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.time.LocalDateTime;

/** 처리 기록(V35)을 적고 읽는다. 적는 쪽은 {@link AdminAuditInterceptor} 하나뿐이다. */
@Service
@RequiredArgsConstructor
public class AdminAuditService {

    private static final String UNKNOWN_ACTOR = "(알 수 없음)";

    private final AdminAuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    /** 아이디를 그때 값으로 굳혀 적는다 — 그 관리자 계정이 나중에 지워져도 누가 했는지 남는다. */
    @Transactional
    public void record(Long actorId, String method, String pattern, String path, Long createdId) {
        String username = actorId == null ? UNKNOWN_ACTOR
                : userRepository.findById(actorId).map(User::getUsername).orElse(UNKNOWN_ACTOR);
        auditLogRepository.save(AdminAuditLog.of(actorId, username, method, pattern, path, createdId,
                LocalDateTime.now()));
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminAuditItem> list(Pageable pageable) {
        return PageResponse.from(auditLogRepository.findAllByOrderByIdDesc(pageable).map(AdminAuditItem::from));
    }
}
