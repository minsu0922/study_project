package project.study.study_project.admin.revision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 문제·문서의 수정 이력(V36) — 고치기 직전 모습을 적어 두고, 되돌릴 때 꺼내 준다.
 *
 * <p>이 서비스는 문제와 문서가 무엇인지 모른다. 받은 객체를 JSON으로 적고, 달라는 타입으로
 * 돌려줄 뿐이다. 무엇을 적을지와 되돌리는 방법은 각 관리 서비스가 정한다.
 */
@Service
@RequiredArgsConstructor
public class ContentRevisionService {

    /** 화면에 보여 줄 이력 수. 이보다 오래된 것도 지우지는 않는다. */
    private static final int LIST_SIZE = 20;

    private final ContentRevisionRepository revisionRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    /**
     * 지금 모습을 적는다. 반드시 고치는 쪽의 트랜잭션에 합류한다 —
     * 수정은 됐는데 옛 모습이 안 남으면 되돌릴 수 없고, 그 사실은 되돌리려 할 때에야 드러난다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void snapshot(RevisionTarget targetType, Long targetId, Object current) {
        try {
            revisionRepository.save(ContentRevision.of(targetType, targetId,
                    objectMapper.writeValueAsString(current), currentUsername(), LocalDateTime.now()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("수정 이력을 적지 못했습니다: " + targetType + " " + targetId, e);
        }
    }

    @Transactional(readOnly = true)
    public List<RevisionItem> list(RevisionTarget targetType, Long targetId) {
        return revisionRepository
                .findByTargetTypeAndTargetIdOrderByIdDesc(targetType, targetId, PageRequest.of(0, LIST_SIZE))
                .stream()
                .map(r -> new RevisionItem(r.getId(), titleOf(r), r.getEditorUsername(), r.getCreatedAt()))
                .toList();
    }

    /** @throws BusinessException 그 대상의 이력이 아니면 COMMON_001 */
    @Transactional(readOnly = true)
    public <T> T read(RevisionTarget targetType, Long targetId, Long revisionId, Class<T> type) {
        ContentRevision revision = revisionRepository
                .findByIdAndTargetTypeAndTargetId(revisionId, targetType, targetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMON_001, "수정 이력을 찾을 수 없습니다."));
        try {
            return objectMapper.readValue(revision.getSnapshotJson(), type);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.COMMON_001, "수정 이력을 읽을 수 없습니다. 형식이 달라졌습니다.");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteAll(RevisionTarget targetType, Long targetId) {
        revisionRepository.deleteAllOf(targetType, targetId);
    }

    private String titleOf(ContentRevision revision) {
        try {
            JsonNode title = objectMapper.readTree(revision.getSnapshotJson()).get("title");
            return title == null || title.isNull() ? null : title.asText();
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** 요청 밖(배치·테스트)에서 불리면 사람이 없다. 그때는 비워 둔다. */
    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long userId)) {
            return null;
        }
        return userRepository.findById(userId).map(User::getUsername).orElse(null);
    }
}
