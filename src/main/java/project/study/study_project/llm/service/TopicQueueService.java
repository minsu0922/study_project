package project.study.study_project.llm.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminTopicQueueRequest;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.llm.domain.TopicQueueItem;
import project.study.study_project.llm.dto.TopicQueueFile;
import project.study.study_project.llm.dto.TopicQueueItemResponse;
import project.study.study_project.llm.repository.TopicQueueItemRepository;
import project.study.study_project.llm.support.TopicQueue;

import java.time.LocalDate;
import java.util.List;

/**
 * 개념 문서 주제 대기열 — 관리자 화면의 입력을 받고, 배치가 남긴 사용 표시를 되돌려 받는다.
 *
 * <h2>왜 생겼나</h2>
 *
 * <p>대기열은 처음에 저장소 파일 하나였다({@code generated/_topics.json}). 배치가 클라우드에서
 * 도니 DB를 볼 수 없어서인데, 그러다 보니 주제 하나 추가하는 데 JSON을 손으로 고쳐야 했다.
 * 쉼표 하나 빠뜨리면 그날 대기열이 통째로 안 읽히고, <b>배치는 그래도 초록불로 끝난다</b>
 * (모델이 주제를 자동으로 고르므로). 잘못을 알아차릴 방법이 없는 입력 방식이었다.
 *
 * <h2>DB가 원본, 파일은 사본</h2>
 *
 * <pre>
 *   관리자 화면 → DB → (내보내기) → generated/_topics.json → 커밋 → 배치가 읽음
 *                ↑                                              ↓
 *                └────── (기동 시 동기화) ── usedAt 도장 ────────┘
 * </pre>
 *
 * <p>양쪽이 다 쓰는 값은 <b>사용 표시 하나뿐</b>이라 충돌이 날 자리가 좁다. 주제·순서·메모는
 * 오직 DB에서만 바뀌고, 사용 표시는 오직 배치만 찍는다.
 *
 * <p><b>파일을 직접 고치는 길도 살려 뒀다.</b> id가 없는 줄은 "사람이 손으로 적은 것"으로 보고
 * DB로 들여온다({@link #syncFrom}). 이게 없으면 대기열을 채우는 방법이 화면 하나로 줄어드는데,
 * 그러면 앱을 띄우지 않고는 주제를 못 넣는다 — 배치는 앱과 무관하게 도는 물건이라
 * 그 의존은 부자연스럽다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopicQueueService {

    private final TopicQueueItemRepository repository;

    /** 대기열이 바뀌면 파일을 다시 내보내야 한다 — 듣는 쪽은 {@link TopicQueueExporter}. */
    private final ApplicationEventPublisher events;

    /** 순서 이동 방향. 문자열("up")을 그대로 받으면 오타가 런타임까지 살아남는다. */
    public enum Direction {
        UP, DOWN
    }

    /* ── 조회 ─────────────────────────────────────────────────── */

    /**
     * 전체 목록 — 대기 중인 것이 위, 이미 쓴 것이 아래.
     *
     * <p><b>다 쓴 주제도 보여 준다.</b> "지난달에 뭘 공부했더라"가 다음 주제를 고를 때 가장
     * 자주 하는 질문이라서다. 파일에는 대기 중인 것만 내보내는 것과 대비되는데, 파일의 목적은
     * "다음에 무엇을 쓸까" 하나뿐이고 화면의 목적은 그것과 기록 둘 다이기 때문이다.
     */
    @Transactional(readOnly = true)
    public List<TopicQueueItemResponse> getAll() {
        return repository.findAllByOrderBySortOrderAsc().stream()
                // 대기 중(false=0)이 먼저 오도록. 같은 그룹 안에서는 정렬 순서가 유지된다
                // (Stream.sorted는 안정 정렬이라 위 sortOrder 순서를 흐트러뜨리지 않는다).
                .sorted((a, b) -> Boolean.compare(!a.isPending(), !b.isPending()))
                .map(TopicQueueItemResponse::from)
                .toList();
    }

    /** 관리자 화면 배지용 — 남은 주제 수. 0이면 배치가 모델 자동 선택으로 돈다는 뜻이다. */
    @Transactional(readOnly = true)
    public long pendingCount() {
        return repository.countByUsedAtIsNull();
    }

    /* ── 변경 ─────────────────────────────────────────────────── */

    /**
     * 주제 추가 — 대기열 <b>맨 뒤</b>에 붙는다.
     *
     * <p>맨 앞이 아닌 이유: 적은 순서가 곧 공부하고 싶은 순서라는 것이 이 대기열의 규칙이고,
     * 새로 떠오른 주제가 늘 가장 급한 것은 아니다. 급하면 순서 이동으로 올리면 된다.
     *
     * <p>같은 분야에 같은 주제가 <b>대기 중</b>이면 막는다(TOPIC_002). 이미 쓴 주제는 막지
     * 않는다 — 다시 다루고 싶은 것은 정당한 요구다.
     */
    @Transactional
    public TopicQueueItemResponse add(AdminTopicQueueRequest request) {
        String topic = request.topic().trim();
        if (repository.existsByDomainAndTopicAndUsedAtIsNull(request.domain(), topic)) {
            throw new BusinessException(ErrorCode.TOPIC_002);
        }

        TopicQueueItem saved = repository.save(TopicQueueItem.pending(
                request.domain(), topic, trimToNull(request.memo()),
                repository.findMaxSortOrder() + 1));

        log.info("주제 대기열 추가: [{}] {} — 커밋해야 다음 배치부터 반영됩니다",
                request.domain(), topic);
        events.publishEvent(new TopicQueueChanged());
        return TopicQueueItemResponse.from(saved);
    }

    /**
     * 삭제 — 되돌릴 수 없다.
     *
     * <p>이미 쓴 항목도 지울 수 있게 열어 뒀다. 기록으로서의 값어치가 있지만, 그건 지우지 말라고
     * 권할 이유이지 <b>못 지우게 막을</b> 이유는 아니다 — 잘못 적은 주제가 목록에 영원히 남으면
     * 화면이 지저분해지고, 그러면 화면 자체를 안 보게 된다.
     */
    @Transactional
    public void delete(Long id) {
        TopicQueueItem item = find(id);
        repository.delete(item);
        log.info("주제 대기열 삭제: [{}] {}", item.getDomain(), item.getTopic());
        events.publishEvent(new TopicQueueChanged());
    }

    /**
     * 순서 이동 — 옆 항목과 순서값을 맞바꾼다.
     *
     * <p><b>왜 이웃과 맞바꾸나.</b> "이 항목을 3번 자리로" 방식은 사이에 낀 항목을 전부 밀어야
     * 해서 한 번의 이동이 여러 행을 건드린다. 맞바꾸기는 항상 두 행이고, 실패해도 두 행만
     * 원래대로 두면 된다.
     *
     * <p><b>이미 쓴 항목은 옮길 수 없다.</b> 순서는 "다음에 무엇을 쓸까"의 규칙이라 이미 쓴
     * 것에는 뜻이 없다. 조용히 무시하지 않고 거절하는 이유는, 눌렀는데 아무 일도 안 일어나면
     * 화면이 고장 난 것처럼 보이기 때문이다.
     *
     * <p>맨 위에서 위로(맨 아래에서 아래로) 누르면 아무 일도 하지 않는다 — 이건 화면에서
     * 버튼을 감출 수도 있는 종류라 오류로 볼 것이 아니다.
     */
    @Transactional
    public void move(Long id, Direction direction) {
        TopicQueueItem item = find(id);
        if (!item.isPending()) {
            throw new BusinessException(ErrorCode.TOPIC_002, "이미 쓴 주제는 순서를 바꿀 수 없습니다.");
        }

        List<TopicQueueItem> pending = repository.findByUsedAtIsNullOrderBySortOrderAsc();
        int index = indexOf(pending, id);
        int target = direction == Direction.UP ? index - 1 : index + 1;
        if (index < 0 || target < 0 || target >= pending.size()) {
            return; // 이미 끝이다 — 할 일이 없다
        }

        TopicQueueItem neighbor = pending.get(target);
        // 두 값을 <바꾸기 전에> 붙잡아 둔다. 맞바꾼 뒤에 비교하면 neighbor는 이미 내 값이라
        // 아래 조건이 <항상> 참이 되어, 멀쩡한 이동에도 보정이 끼어든다(테스트가 잡은 버그).
        int mine = item.getSortOrder();
        int theirs = neighbor.getSortOrder();
        item.changeOrder(theirs);
        neighbor.changeOrder(mine);

        // 순서값이 같으면(옛 데이터나 흡수 과정에서 겹칠 수 있다) 맞바꿔도 목록이 그대로다.
        // 그때는 이웃을 한 칸 밀어 확실히 갈라 준다 — "눌렀는데 안 움직인다"를 막는 보정.
        if (mine == theirs) {
            neighbor.changeOrder(mine + (direction == Direction.UP ? 1 : -1));
        }
        events.publishEvent(new TopicQueueChanged());
    }

    /* ── 파일 → DB 동기화 ─────────────────────────────────────── */

    /**
     * 기동 시 파일을 읽어 DB에 반영한다 — <b>사용 표시 되돌려 받기 + 손으로 적은 줄 흡수</b>.
     *
     * <p>배치는 클라우드에서 돌기 때문에 DB에 아무것도 못 쓴다. 대신 파일에 날짜를 찍어
     * 커밋해 두고, 그 결과를 앱이 켜질 때 여기서 읽는다 — {@code generated/*.json}의 초안
     * 흡수와 완전히 같은 구조다(택배함에서 물건 꺼내오기).
     *
     * <p><b>id가 열쇠다.</b> 주제 글자로 짝지으면 화면에서 오타를 고친 순간 짝이 끊겨,
     * 배치가 쓴 주제가 영원히 "대기 중"으로 남는다. id가 없는 줄만 사람이 손으로 적은 것으로
     * 보고 새로 들여온다.
     *
     * <p><b>형식이 틀린 줄은 조용히 건너뛴다.</b> 여기서 예외를 던지면 부팅이 실패하는데,
     * 대기열은 부가 기능이라 그것 때문에 퀴즈 풀이까지 죽으면 안 된다. 게다가 같은 파일을
     * 읽는 배치가 이미 "건너뛴 항목" 경고를 Actions 요약에 띄운다 — 알림이 이미 있는 자리다.
     */
    @Transactional
    public SyncResult syncFrom(TopicQueueFile file) {
        if (file == null || file.topics() == null || file.topics().isEmpty()) {
            return new SyncResult(0, 0);
        }

        int applied = 0;
        int imported = 0;
        int nextOrder = repository.findMaxSortOrder();

        for (TopicQueueFile.Entry entry : file.topics()) {
            if (entry == null) {
                continue;
            }
            if (entry.id() != null) {
                applied += applyUsed(entry) ? 1 : 0;
                continue;
            }
            TopicQueueItem adopted = adopt(entry, nextOrder + 1);
            if (adopted != null) {
                nextOrder++;
                imported++;
            }
        }

        if (applied + imported > 0) {
            log.info("주제 대기열 동기화: 사용 표시 {}건 반영, 파일에 손으로 적은 주제 {}건 흡수",
                    applied, imported);
            events.publishEvent(new TopicQueueChanged());
        }
        return new SyncResult(applied, imported);
    }

    /** 파일 → DB 동기화 결과. 러너가 로그로 남긴다. */
    public record SyncResult(int usedApplied, int imported) {
    }

    /** 배치가 찍은 날짜를 DB에 반영한다. 이미 반영됐거나 지운 항목이면 아무 일도 하지 않는다. */
    private boolean applyUsed(TopicQueueFile.Entry entry) {
        if (entry.isPending()) {
            return false;
        }
        LocalDate usedAt = parseDate(entry.usedAt());
        if (usedAt == null) {
            return false;
        }
        // 화면에서 지운 항목의 도장이 파일에 남아 있을 수 있다 — 그건 무시한다.
        // 여기서 되살리면 "지웠는데 다시 나타나는" 최악의 동작이 된다.
        return repository.findById(entry.id())
                .filter(TopicQueueItem::isPending)
                .map(item -> {
                    item.markUsed(usedAt);
                    return true;
                })
                .orElse(false);
    }

    /** 손으로 적은 줄을 DB로 들여온다. 형식이 틀리거나 이미 있으면 {@code null}. */
    private TopicQueueItem adopt(TopicQueueFile.Entry entry, int sortOrder) {
        Domain domain = TopicQueue.parseDomain(entry.domain());
        if (domain == null || entry.topic() == null || entry.topic().isBlank()) {
            return null;
        }
        String topic = entry.topic().trim();
        if (repository.existsByDomainAndTopicAndUsedAtIsNull(domain, topic)) {
            return null; // 화면에서 이미 넣어 둔 주제 — 두 벌이 되면 문서도 두 번 나온다
        }
        // usedAt이 있는 줄을 대기로 들여오면 같은 주제로 문서를 한 번 더 만든다(엔티티 주석).
        return repository.save(TopicQueueItem.imported(
                domain, topic, trimToNull(entry.memo()), sortOrder, parseDate(entry.usedAt())));
    }

    /* ── 도우미 ───────────────────────────────────────────────── */

    private TopicQueueItem find(Long id) {
        return repository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.TOPIC_001));
    }

    private int indexOf(List<TopicQueueItem> items, Long id) {
        for (int i = 0; i < items.size(); i++) {
            if (id.equals(items.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }

    /** {@code "2026-08-19"} → 날짜. 손으로 고쳐 깨진 값이면 {@code null}(무시하고 계속). */
    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
            log.warn("주제 대기열의 usedAt을 읽지 못해 무시합니다: {}", raw);
            return null;
        }
    }

    private String trimToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
