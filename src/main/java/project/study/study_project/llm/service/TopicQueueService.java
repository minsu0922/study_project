package project.study.study_project.llm.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminTopicQueueRequest;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.llm.domain.TopicQueueItem;
import project.study.study_project.llm.dto.TopicQueueFile;
import project.study.study_project.llm.dto.TopicQueueItemResponse;
import project.study.study_project.llm.repository.TopicQueueItemRepository;
import project.study.study_project.llm.support.TopicQueue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 개념 문서 <b>주제 범위</b> 목록 — 관리자 화면의 입력을 받고, 배치가 남긴 사용 기록을 되돌려 받는다.
 *
 * <h2>한 줄은 범위다(V11)</h2>
 *
 * <p>"Spring", "JVM 메모리" 같은 <b>범위</b>를 적어 두면, 배치가 문서일마다 범위 하나를 골라
 * 그 안에서 세부 주제를 정해 문서를 쓴다. 줄은 소진되지 않고 <b>사용 기록만 쌓인다</b> —
 * 다음 차례가 오면 같은 범위에서 다른 주제를 캔다(이미 쓴 제목은 프롬프트의 중복 회피 목록이 막는다).
 *
 * <h2>왜 생겼나</h2>
 *
 * <p>목록은 처음에 저장소 파일 하나였다({@code generated/_topics.json}). 배치가 클라우드에서
 * 도니 DB를 볼 수 없어서인데, 그러다 보니 한 줄 추가하는 데 JSON을 손으로 고쳐야 했다.
 * 쉼표 하나 빠뜨리면 그날 목록이 통째로 안 읽히고, <b>배치는 그래도 초록불로 끝난다</b>
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

    /**
     * 순서 이동 방향. 문자열("up")을 그대로 받으면 오타가 런타임까지 살아남는다.
     *
     * <p>{@link #TOP}은 2026-09-05에 더했다. {@link #UP}만 있으면 <b>한 칸씩</b>이라
     * 40번째 줄을 맨 위로 올리는 데 39번을 눌러야 한다. 게다가 목록이 20줄씩 나뉘고
     * 검색 중에는 이동이 잠기므로(화면 주석 참고), 실제로는 "찾아서 올리기"가 불가능했다.
     * 대기열이 67줄까지 늘면서 순서를 다시 잡는 일 자체가 막힌 상태였다.
     */
    public enum Direction {
        UP, DOWN, TOP
    }

    /*
     * TopicSort(MANUAL / NEXT_UP)가 여기 있었다 — 2026-09-14에 만들고 같은 날 지웠다.
     *
     * 고르게 한 것 자체가 잘못이었다. 여든 줄 중 일흔넷이 아직 안 쓴 것이라 규칙 ①②가
     * 전부 동점이고, 그러면 ③(내가 놓은 순서)이 순서를 통째로 정한다 — 두 정렬이 사실상
     * <같은 목록>이었다. 같은 것을 두 이름으로 내놓고 고르라고 한 셈이다.
     *
     * 목록은 이제 늘 "먼저 나올 순서"다(아래 search). 화면의 ↑↓⤒는 그대로 쓴다 — 안 쓴 줄
     * 사이에서는 그 순서가 곧 내가 놓은 순서라 눌러 보면 실제로 움직인다.
     *
     * getAll()은 여전히 sortOrder 순으로 돌려준다. 내보내기와 pickNext가 그 순서를 전제하고,
     * 무엇보다 sortOrder는 규칙 ③ 그 자체라 사라진 개념이 아니다 — 사라진 것은 <보기 선택>뿐이다.
     */

    /**
     * 사용 이력으로 거르기 — 2026-09-14 신설.
     *
     * <p>대기열이 여든 줄까지 늘면서 <b>"안 쓴 것만 보고 싶다"</b>가 가장 잦은 요구가 됐다.
     * 안 쓴 범위가 다음 차례를 먼저 가져가므로, 순서를 손볼 때 실제로 다투는 것이 그 무리다.
     * 반대로 "쓴 적 있는 것"은 우물이 마르는지 살필 때 본다(편수가 쌓인 줄을 갈아 끼운다).
     */
    public enum TopicUsage {
        ALL, NEVER_USED, USED
    }

    /* ── 조회 ─────────────────────────────────────────────────── */

    /**
     * 전체 목록 — <b>사람이 정한 순서</b> 그대로. 다음 차례인 한 줄에만 표시가 붙는다.
     *
     * <p><b>왜 "다음 차례 순"으로 정렬하지 않나.</b> 그러면 ↑↓ 버튼을 눌러도 목록이 안 움직이는
     * 때가 생긴다(사용 기록이 정렬을 지배하므로). 사람이 정한 순서를 그대로 보여 주고 다음
     * 차례만 표시하는 편이, 눈에 보이는 것과 손으로 하는 것이 어긋나지 않는다.
     */
    @Transactional(readOnly = true)
    public List<TopicQueueItemResponse> getAll() {
        return toResponses(repository.findAllByOrderBySortOrderAsc());
    }

    /**
     * 검색·쪽 나누기를 얹은 목록 — 2026-08-29 신설. 범위가 67개까지 늘어 한 화면에 다 담기지 않는다.
     *
     * <h2>왜 DB가 아니라 메모리에서 자르나</h2>
     *
     * <p>이 목록은 두 가지를 <b>전체를 봐야만</b> 정할 수 있다.
     * <ul>
     *   <li>{@code next}(다음 차례) — {@link #pickNext}가 목록 전체를 훑어 하나를 고른다.
     *       한 쪽만 넘겨받아 계산하면 "3쪽에도 다음 차례가 있다"는 거짓말이 된다.
     *   <li>{@code order}(전체에서 몇 번째) — 검색으로 걸러진 줄에도 <b>원래 차례</b>가 붙어야 한다.
     * </ul>
     * 둘 다 전체 목록이 손에 있어야 하므로, DB에 페이징을 맡겨도 어차피 전부 읽어야 한다.
     * 범위는 문서일마다 하나씩 늘어(4일에 1개) 몇백 개가 한계이므로 이 방식이 부담이 되지 않는다.
     * 그보다 커지면 그때는 {@code next}를 별도 조회로 떼어내는 것이 먼저다.
     *
     * @param q 주제·메모에서 찾을 말. 비어 있으면 거르지 않는다(대소문자 무시)
     */
    @Transactional(readOnly = true)
    public PageResponse<TopicQueueItemResponse> search(String q, DomainCode domain, TopicUsage usage,
                                                       Pageable pageable) {
        List<TopicQueueItemResponse> all = getAll();

        String keyword = (q == null) ? "" : q.trim().toLowerCase();
        List<TopicQueueItemResponse> matched = all.stream()
                .filter(item -> keyword.isEmpty() || matches(item, keyword))
                .filter(item -> domain == null || item.domain() == domain)
                .filter(item -> matchesUsage(item, usage))
                .toList();

        // 거른 <뒤에> 줄 세운다. 순서를 먼저 잡아도 결과는 같지만, 거르는 일이 늘 더 싸다.
        // order 칸은 그대로 둔다 — 그 값은 <내가 놓은 순서에서 몇 번째>라는 뜻이고,
        // 여기서 줄이 다시 서도 "원래 자리"를 알려 주는 것이 이 칸의 쓸모다.
        matched = byNextUp(matched);

        int from = (int) Math.min(pageable.getOffset(), matched.size());
        int to = Math.min(from + pageable.getPageSize(), matched.size());
        // PageImpl을 거쳐 PageResponse.from을 탄다 — 변환 지점을 하나로 두면 응답 규격이
        // 바뀔 때 고칠 곳도 하나다(PageResponse 클래스 주석).
        return PageResponse.from(new PageImpl<>(matched.subList(from, to), pageable, matched.size()));
    }

    /**
     * <b>배치가 꺼낼 순서</b>로 줄 세운다 — 안 쓴 것 먼저 → 가장 오래전에 쓴 것 → 내가 놓은 순서.
     *
     * <p>{@link #pickNext}가 하나를 고르는 규칙을 목록 전체에 편 것이다. 그래서 <b>첫 줄이 곧
     * "다음 차례"</b>가 된다 — 맨 위와 배지가 어긋나면 두 규칙이 갈라진 것이고, 그것이
     * 이 화면에서 가장 먼저 눈에 띄어야 할 고장이다.
     *
     * <p>{@code order}(내가 놓은 순서에서 몇 번째)를 마지막 기준으로 쓴다. {@code pickNext}가
     * "먼저 온 항목이 자리를 지킨다"로 같은 타이브레이커를 쓰므로 둘의 답이 맞아떨어진다.
     * 지금은 여든 줄 중 일흔넷이 안 쓴 것이라 <b>사실상 이 마지막 기준이 전부를 정한다</b> —
     * 화면의 ↑↓가 여전히 뜻을 갖는 이유다.
     */
    private List<TopicQueueItemResponse> byNextUp(List<TopicQueueItemResponse> items) {
        return items.stream()
                // 안 쓴 것이 먼저다. LocalDate는 null을 비교할 수 없어 <안 썼는가>를 별도 축으로 뽑는다 —
                // nullsFirst로 묶어도 되지만, 규칙 문장("안 쓴 것 먼저")이 코드에 그대로 보이는 편이 낫다.
                .sorted(Comparator.comparing((TopicQueueItemResponse i) -> i.lastUsedAt() != null)
                        .thenComparing(TopicQueueItemResponse::lastUsedAt,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparingInt(TopicQueueItemResponse::order))
                .toList();
    }

    /**
     * 사용 이력 거르기 — 판정 기준은 {@code lastUsedAt}이다.
     *
     * <p>{@code usedCount}로 재지 않는 이유: 둘이 어긋난 줄이 실제로 있었다(배치가 날짜를
     * 찍은 뒤 문서가 거절된 경우). 다음 차례를 정할 때 보는 것도 날짜이므로
     * ({@link TopicQueueItem#isNeverUsed}), 거르는 기준도 같은 값이어야 화면과 실제가 맞는다.
     */
    private boolean matchesUsage(TopicQueueItemResponse item, TopicUsage usage) {
        return switch (usage == null ? TopicUsage.ALL : usage) {
            case ALL -> true;
            case NEVER_USED -> item.lastUsedAt() == null;
            case USED -> item.lastUsedAt() != null;
        };
    }

    /** 주제와 메모에서 찾는다. 분야는 이제 드롭다운으로 거르므로(2026-09-14) 검색어에 넣지 않는다. */
    private boolean matches(TopicQueueItemResponse item, String keyword) {
        return item.topic().toLowerCase().contains(keyword)
                || (item.memo() != null && item.memo().toLowerCase().contains(keyword));
    }

    /** 목록 → 응답. {@code next}와 {@code order}를 여기서 한 번에 붙인다(둘 다 전체가 있어야 한다). */
    private List<TopicQueueItemResponse> toResponses(List<TopicQueueItem> items) {
        TopicQueueItem next = pickNext(items);
        List<TopicQueueItemResponse> responses = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            TopicQueueItem item = items.get(i);
            responses.add(TopicQueueItemResponse.from(item, item == next, i + 1));
        }
        return responses;
    }

    /**
     * 다음 문서일에 쓰일 범위 — <b>안 쓴 것 먼저 → 가장 오래 안 쓴 것 → 적어 둔 순서</b>.
     *
     * <p>배치가 파일을 보고 내리는 판단({@code TopicQueue.next})과 <b>같은 규칙</b>이다.
     * 두 곳에 있는 것이 마음에 걸리지만, 한쪽은 JSON 줄을 보고 한쪽은 엔티티를 본다 —
     * 공통 타입을 만들어 묶으면 파일 형식과 DB 스키마가 한 몸이 되어 더 나쁘다.
     * 대신 규칙이 어긋나면 화면의 "다음 차례" 표시가 실제와 달라지므로 <b>눈에 보인다</b>.
     *
     * <p>목록이 {@code sortOrder} 순으로 들어오고 <b>더 앞선 것만</b> 갱신하므로,
     * 조건이 같을 때는 먼저 온 항목이 자리를 지킨다(= 사람이 정한 순서가 타이브레이커).
     */
    private TopicQueueItem pickNext(List<TopicQueueItem> items) {
        TopicQueueItem best = null;
        for (TopicQueueItem item : items) {
            if (best == null || isEarlier(item, best)) {
                best = item;
            }
        }
        return best;
    }

    private boolean isEarlier(TopicQueueItem a, TopicQueueItem b) {
        if (a.isNeverUsed() || b.isNeverUsed()) {
            return a.isNeverUsed() && !b.isNeverUsed();
        }
        return a.getLastUsedAt().isBefore(b.getLastUsedAt());
    }

    /** 관리자 화면 배지용 — 등록된 범위 수. 0이면 배치가 모델 자동 선택으로 돈다는 뜻이다. */
    @Transactional(readOnly = true)
    public long count() {
        return repository.count();
    }

    /* ── 변경 ─────────────────────────────────────────────────── */

    /**
     * 주제 범위 추가 — 목록 <b>맨 뒤</b>에 붙는다. 단, 아직 안 쓴 범위라 <b>다음 차례는
     * 곧바로 받는다</b>(위 {@link #pickNext} 규칙).
     *
     * <p>맨 앞에 꽂지 않는 이유: 이미 넣어 둔 범위들과 순서를 다투게 만들 이유가 없다.
     * 안 쓴 범위끼리는 적어 둔 순서대로 차례가 오고, 급하면 ↑로 올리면 된다.
     *
     * <p>같은 분야에 같은 범위가 이미 있으면 막는다(TOPIC_002). V10에서는 "대기 중인 것만"
     * 봤는데, 범위는 소진되지 않으므로 이제 같은 이름이 둘 있으면 <b>그 범위만 두 배로 자주</b>
     * 나올 뿐이다.
     */
    @Transactional
    public TopicQueueItemResponse add(AdminTopicQueueRequest request) {
        String topic = request.topic().trim();
        if (repository.existsByDomainAndTopic(request.domain(), topic)) {
            throw new BusinessException(ErrorCode.TOPIC_002);
        }

        TopicQueueItem saved = repository.save(TopicQueueItem.fresh(
                request.domain(), topic, trimToNull(request.memo()),
                repository.findMaxSortOrder() + 1));

        log.info("주제 범위 추가: [{}] {} — 커밋해야 다음 배치부터 반영됩니다",
                request.domain(), topic);
        events.publishEvent(new TopicQueueChanged());
        // 방금 넣은 줄은 맨 뒤이므로 차례도 마지막이다. next는 false로 둔다 — 실제로는 안 쓴
        // 범위라 다음 차례를 곧바로 받지만, 그 판정은 목록 전체를 봐야 하고 화면은 추가 직후
        // 목록을 다시 불러 정확한 값을 받는다. 여기서 짐작해 true를 넣으면 그 순간만 맞고
        // 다른 안 쓴 범위가 앞에 있을 때 틀린다.
        return TopicQueueItemResponse.from(saved, false, (int) repository.count());
    }

    /**
     * 주제 범위 수정 — 2026-09-14 신설. <b>사용 기록과 순서는 그대로 둔다.</b>
     *
     * <p><b>왜 이제야 생겼나.</b> 지금까지 제목을 고치는 길이 없어서 삭제 후 재등록으로
     * 우회해야 했는데, 그러면 그 줄이 <b>아직 안 쓴 범위</b>로 되살아나 곧바로 차례를 가져간다
     * ({@code TopicQueueItem.edit} 주석). 파일을 손으로 고치는 길도 막혀 있다 —
     * {@link #syncFrom}은 id가 붙은 줄에서 사용 기록만 읽고, 그 뒤 내보내기가 DB 내용으로
     * 파일을 덮는다. 결국 <b>DB에 직접 UPDATE를 날리는 것 말고는 방법이 없었다.</b>
     *
     * <p><b>중복 검사가 add와 다르다.</b> 자기 자신을 빼고 본다
     * ({@code existsByDomainAndTopicAndIdNot}) — 안 그러면 메모만 고쳐도 막힌다.
     *
     * <p>바뀐 뒤 {@link TopicQueueChanged}를 알려 파일을 다시 내보낸다. 그래서 <b>고칠 때마다
     * 커밋이 필요</b>하고, 여러 줄을 손볼 거면 몰아서 고친 뒤 한 번 커밋하는 편이 낫다.
     */
    @Transactional
    public TopicQueueItemResponse update(Long id, AdminTopicQueueRequest request) {
        TopicQueueItem item = find(id);
        String topic = request.topic().trim();
        if (repository.existsByDomainAndTopicAndIdNot(request.domain(), topic, id)) {
            throw new BusinessException(ErrorCode.TOPIC_002);
        }

        DomainCode before = item.getDomain();
        item.edit(request.domain(), topic, trimToNull(request.memo()));

        // 분야가 바뀐 것은 따로 남긴다. 이 값이 사흘치 문제의 분야까지 정하므로, 나중에
        // "왜 이날 문제가 딴 분야지"를 되짚을 때 이 줄 하나가 실마리가 된다.
        if (!Objects.equals(before, request.domain())) { // != 는 참조 비교라 매번 "바뀜"이 된다
            log.info("주제 범위 분야 변경: #{} {} → {} — 이 범위로 만들 문서와 사흘치 문제의 분야가 함께 바뀝니다",
                    id, before, request.domain());
        }
        log.info("주제 범위 수정: [{}] {} — 커밋해야 다음 배치부터 반영됩니다", request.domain(), topic);
        events.publishEvent(new TopicQueueChanged());

        // add와 같은 이유로 next는 false다 — 판정에 목록 전체가 필요하고, 화면은 수정 직후
        // 목록을 다시 불러 정확한 값을 받는다.
        return TopicQueueItemResponse.from(item, false, (int) repository.count());
    }

    /**
     * 고른 범위들을 <b>한꺼번에</b> 맨 위로 — 2026-09-14 신설.
     *
     * <p><b>왜 필요한가.</b> {@link Direction#TOP}은 한 줄씩이라 스무 개를 당기려면 스무 번
     * 눌러야 하고, 더 나쁜 것은 <b>누른 순서의 역순으로 쌓인다</b>는 점이다(나중에 누른 것이
     * 위로 간다). 셋만 올려도 순서가 뒤집혀, 올린 뒤 다시 ↑↓로 정렬해야 했다.
     *
     * <p><b>클릭 순서가 아니라 지금 목록 순서를 지킨다.</b> 고른 것들끼리의 상대 순서는
     * 건드리지 않고 덩어리째 앞으로 옮긴다. 클릭 순서를 쓰면 스무 개를 고르는 동안 순서를
     * 머리로 기억해야 하고, 체크를 한 번 풀었다 다시 누르면 그 줄만 맨 뒤로 간다 —
     * <b>고르는 일과 순서를 정하는 일은 다른 일</b>이다. 세부 순서는 옮긴 뒤 ↑↓로 잡으면 된다.
     *
     * <p><b>전부 다시 번호를 매긴다.</b> {@link #move}가 두 행만 맞바꾸는 것과 다른데,
     * 여기서는 어차피 고른 것 전부와 그 앞에 있던 것 전부가 자리를 옮긴다 — 최소 갱신을
     * 계산해 봐야 거의 전부이고, 그 계산이 틀리면 순서값이 겹쳐 정렬이 흔들린다.
     * 여든 줄 규모에서 한 번의 통째 번호 매기기가 제일 단순하고 안전하다.
     *
     * <p>없는 id는 조용히 지나간다. 여러 쪽에 걸쳐 고르는 동안 다른 곳에서 지워졌을 수 있는데,
     * 그 하나 때문에 나머지 열아홉의 이동을 통째로 막을 이유가 없다.
     */
    @Transactional
    public void moveToTop(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Set<Long> wanted = new LinkedHashSet<>(ids);
        List<TopicQueueItem> all = repository.findAllByOrderBySortOrderAsc();

        List<TopicQueueItem> picked = all.stream().filter(i -> wanted.contains(i.getId())).toList();
        if (picked.isEmpty()) {
            return;   // 고른 것이 전부 사라졌다 — 파일을 괜히 다시 내보내지 않는다
        }
        List<TopicQueueItem> rest = all.stream().filter(i -> !wanted.contains(i.getId())).toList();

        int order = 1;
        for (TopicQueueItem item : picked) {
            item.changeOrder(order++);
        }
        for (TopicQueueItem item : rest) {
            item.changeOrder(order++);
        }

        log.info("주제 범위 {}건을 맨 위로 옮겼습니다 — 커밋해야 다음 배치부터 반영됩니다", picked.size());
        events.publishEvent(new TopicQueueChanged());
    }

    /**
     * 삭제 — 되돌릴 수 없다. 그 범위로 만든 문서는 남고, <b>몇 편 썼는지의 기록만</b> 사라진다.
     *
     * <p>범위가 말랐을 때 갈아 끼우는 것이 정상 사용법이라 삭제는 자주 쓰인다. 그래서 확인
     * 절차를 화면 쪽(버튼 두 번 누르기)에만 두고 서버는 막지 않는다.
     */
    @Transactional
    public void delete(Long id) {
        TopicQueueItem item = find(id);
        repository.delete(item);
        log.info("주제 범위 삭제: [{}] {} (이 범위로 {}편 썼음)",
                item.getDomain(), item.getTopic(), item.getUsedCount());
        events.publishEvent(new TopicQueueChanged());
    }

    /**
     * 순서 이동 — 옆 항목과 순서값을 맞바꾼다.
     *
     * <p><b>왜 이웃과 맞바꾸나.</b> "이 항목을 3번 자리로" 방식은 사이에 낀 항목을 전부 밀어야
     * 해서 한 번의 이동이 여러 행을 건드린다. 맞바꾸기는 항상 두 행이고, 실패해도 두 행만
     * 원래대로 두면 된다.
     *
     * <p><b>이미 쓴 범위도 옮길 수 있다</b>(V11에서 바뀐 점). 범위는 소진되지 않으므로 순서는
     * 계속 뜻을 갖는다 — 다만 전부 한 번씩 쓰인 뒤로는 "가장 오래 안 쓴 것"이 차례를 지배해서,
     * 순서는 <b>사용 시각이 같을 때의 기준</b>으로 물러난다.
     *
     * <p>맨 위에서 위로(맨 아래에서 아래로) 누르면 아무 일도 하지 않는다 — 이건 화면에서
     * 버튼을 감출 수도 있는 종류라 오류로 볼 것이 아니다.
     */
    @Transactional
    public void move(Long id, Direction direction) {
        TopicQueueItem item = find(id);

        List<TopicQueueItem> all = repository.findAllByOrderBySortOrderAsc();
        int index = indexOf(all, id);

        // 맨 위로는 <맞바꾸기가 아니라 밀어내기>다. 한 칸 이동과 같은 방식으로 처리할 수 없다 —
        // 1번과 자리를 맞바꾸면 원래 1번이 내가 있던 자리(40번째)로 떨어진다. 사람이 기대하는
        // 것은 "내가 맨 위로 가고 나머지는 한 칸씩 밀린다"이지 "1번과 자리를 바꾼다"가 아니다.
        if (direction == Direction.TOP) {
            moveToTop(item, all, index);
            return;
        }

        int target = direction == Direction.UP ? index - 1 : index + 1;
        if (index < 0 || target < 0 || target >= all.size()) {
            return; // 이미 끝이다 — 할 일이 없다
        }

        TopicQueueItem neighbor = all.get(target);
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

    /**
     * 맨 위로 — 나를 1번으로 올리고 위에 있던 것들을 <b>한 칸씩</b> 아래로 민다.
     *
     * <p><b>왜 전체를 다시 번호 매기나.</b> {@code sortOrder}는 지금까지 맞바꾸기로만 다뤄져
     * 값이 연속이라는 보장이 없다(옛 데이터·흡수 과정에서 겹치기도 한다 — {@link #move}의
     * 보정 주석 참고). 그 위에서 "1번 것보다 하나 작은 값"을 계산하면 음수로 내려가거나
     * 이미 있는 값과 겹친다. 옮기는 김에 0부터 다시 매기면 그 불확실성이 사라지고,
     * 목록이 67줄 규모라 한 번에 다시 매기는 비용도 무시할 만하다.
     *
     * <p>이미 맨 위면 아무 일도 하지 않는다 — 눌러도 파일이 다시 나가지 않아야
     * "커밋할 것이 계속 생기는" 상태가 안 된다({@code SnapshotExporter}의 판단과 같다).
     */
    private void moveToTop(TopicQueueItem item, List<TopicQueueItem> all, int index) {
        if (index <= 0) {
            return; // 없는 항목이거나 이미 1번이다
        }
        item.changeOrder(0);
        for (int i = 0, order = 1; i < all.size(); i++) {
            if (i != index) {
                all.get(i).changeOrder(order++);
            }
        }
        events.publishEvent(new TopicQueueChanged());
    }

    /**
     * 사용 기록을 지워 <b>다음 차례 1순위</b>로 되돌린다 — 2026-09-05 신설.
     *
     * <p>기록은 배치가 <b>문서를 만들기로 정한 시점</b>에 찍히므로, 그 뒤 문서가 거절되거나
     * 지워지면 실물 없이 기록만 남는다. 그 상태에서는 차례가 영영 안 돌아온다 —
     * 안 쓴 범위가 수십 개 남아 있는 한 계속 뒤로 밀리기 때문이다.
     * 사유와 되돌리는 방식은 {@link TopicQueueItem#clearUsage()} 주석에 적었다.
     *
     * <p><b>순서는 건드리지 않는다.</b> 이력을 지우면 "안 쓴 범위" 무리에 합류할 뿐이고,
     * 그 안에서 몇 번째인지는 여전히 사람이 정한 순서가 정한다. 맨 위로 올리고 싶으면
     * {@link Direction#TOP}을 따로 부르면 된다 — 두 가지를 한 버튼에 묶으면 "이력만 지우고
     * 순서는 두고 싶다"를 할 수 없게 된다.
     */
    @Transactional
    public void resetUsage(Long id) {
        find(id).clearUsage();
        events.publishEvent(new TopicQueueChanged());
    }

    /* ── 파일 → DB 동기화 ─────────────────────────────────────── */

    /**
     * 기동 시 파일을 읽어 DB에 반영한다 — <b>사용 기록 되돌려 받기 + 손으로 적은 줄 흡수</b>.
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
            log.info("주제 범위 동기화: 사용 기록 {}건 반영, 파일에 손으로 적은 범위 {}건 흡수",
                    applied, imported);
            events.publishEvent(new TopicQueueChanged());
        }
        return new SyncResult(applied, imported);
    }

    /** 파일 → DB 동기화 결과. 러너가 로그로 남긴다. */
    public record SyncResult(int usedApplied, int imported) {
    }

    /**
     * 배치가 적은 사용 기록을 DB에 반영한다. 이미 반영됐거나 지운 항목이면 아무 일도 하지 않는다.
     *
     * <p>"이미 반영됐는가"는 엔티티가 판정한다({@code recordUse}) — 날짜가 더 최근일 때만
     * 받아들인다. 여기서 무조건 반영하면 앱을 켤 때마다 편수가 불어난다.
     */
    private boolean applyUsed(TopicQueueFile.Entry entry) {
        LocalDate lastUsedAt = parseDate(entry.lastUsedAt());
        if (lastUsedAt == null) {
            return false;
        }
        // 화면에서 지운 항목의 기록이 파일에 남아 있을 수 있다 — 그건 무시한다.
        // 여기서 되살리면 "지웠는데 다시 나타나는" 최악의 동작이 된다.
        return repository.findById(entry.id())
                .map(item -> {
                    boolean changed = item.getLastUsedAt() == null
                            || lastUsedAt.isAfter(item.getLastUsedAt());
                    item.recordUse(lastUsedAt, entry.usedCount());
                    return changed;
                })
                .orElse(false);
    }

    /** 손으로 적은 줄을 DB로 들여온다. 형식이 틀리거나 이미 있으면 {@code null}. */
    private TopicQueueItem adopt(TopicQueueFile.Entry entry, int sortOrder) {
        DomainCode domain = TopicQueue.parseDomain(entry.domain());
        if (domain == null || entry.topic() == null || entry.topic().isBlank()) {
            return null;
        }
        String topic = entry.topic().trim();
        if (repository.existsByDomainAndTopic(domain, topic)) {
            return null; // 화면에서 이미 넣어 둔 범위 — 두 벌이면 그 범위만 두 배로 자주 나온다
        }
        // 사용 기록까지 함께 들여온다. 기록을 버리면 그 범위가 "새것"이 되어 곧바로 다음 차례가
        // 되고, 순환이 한쪽으로 쏠린다(엔티티 imported 주석).
        return repository.save(TopicQueueItem.imported(
                domain, topic, trimToNull(entry.memo()), sortOrder,
                parseDate(entry.lastUsedAt()), entry.usedCount() == null ? 0 : entry.usedCount()));
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
