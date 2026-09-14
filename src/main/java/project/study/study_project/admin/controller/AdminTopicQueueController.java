package project.study.study_project.admin.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.admin.dto.AdminTopicQueueMoveRequest;
import project.study.study_project.global.common.Domain;
import project.study.study_project.admin.dto.AdminTopicQueueRequest;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.llm.dto.TopicQueueItemResponse;
import project.study.study_project.llm.service.TopicQueueService;

import java.util.Map;

/**
 * 개념 문서 주제 대기열 관리 API — 2026-08-19 신설.
 *
 * <p>{@code /api/admin/**} 아래라 SecurityConfig의 {@code hasRole(ADMIN)}이 일괄 적용된다
 * (컨트롤러에 권한 코드를 두지 않는 것이 이 프로젝트의 규칙).
 *
 * <h2>페이징을 나중에 붙였다 (2026-08-29)</h2>
 *
 * <p>처음에는 일부러 두지 않았다. 대기열은 "다음에 뭘 쓸까"를 적어 두는 곳이라 수십 줄
 * 규모라고 봤고, <b>순서를 바꾸는 화면에서 쪽이 갈리면 맨 아래 항목을 다음 쪽 첫 항목 위로
 * 올릴 수 없다</b>고 적어 두었다. 그런데 범위가 67개까지 늘면서 한 화면에 담기지 않게 됐다.
 *
 * <p>그때 적은 걱정은 <b>절반만 맞았다</b>. 이동은 여전히 전체 순서에서 일어나므로 쪽이
 * 갈려도 못 하는 일은 없다 — 다만 옮긴 항목이 <b>화면 밖으로 사라진다</b>(앞 쪽으로 갔으므로).
 * 못 하게 되는 것이 아니라 안 보이게 되는 것이라, 화면이 "앞 쪽으로 옮겼습니다"라고
 * 말해 주면 된다.
 *
 * <p><b>검색 중에는 이동을 막는다.</b> 이건 진짜 문제다 — 걸러진 목록에서 "한 칸 위"는
 * 화면 위의 줄이 아니라 전체 순서의 이웃이라, 누르면 아무 일도 안 일어난 것처럼 보인다.
 * 화면이 그 버튼을 잠그고 이유를 적는다.
 *
 * <p><b>수정(PUT)이 없다.</b> 주제 글자를 고치는 것은 "지우고 다시 넣기"와 결과가 같은데,
 * 수정을 열면 <b>배치가 이미 파일로 들고 나간 주제의 글자가 바뀌는</b> 창이 생긴다. 그러면
 * 문서는 옛 주제로 나오고 화면에는 새 주제가 적혀 있어, 나중에 무엇이 맞는지 알 수 없다.
 */
@RestController
@RequestMapping("/api/admin/topic-queue")
@RequiredArgsConstructor
public class AdminTopicQueueController {

    private final TopicQueueService topicQueueService;

    /**
     * 목록 — 사람이 정한 순서 그대로. 다음 차례인 한 줄에 {@code next=true}가 붙는다.
     *
     * <p>2026-08-29부터 검색·쪽 나누기를 받는다(범위가 67개까지 늘었다).
     * {@code q}는 주제·메모에서 찾고, 비우면 전체다. 예: {@code ?q=트랜잭션&page=0&size=20}
     *
     * <p>각 줄의 {@code order}는 <b>전체 목록에서 몇 번째인가</b>다 — 화면의 행 번호가 아니다.
     * 검색으로 걸러도 그 줄의 원래 차례가 보여야 하기 때문이다.
     */
    @GetMapping
    public ApiResponse<PageResponse<TopicQueueItemResponse>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Domain domain,
            @RequestParam(defaultValue = "ALL") TopicQueueService.TopicUsage usage,
            @RequestParam(defaultValue = "MANUAL") TopicQueueService.TopicSort sort,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.ok(topicQueueService.search(q, domain, usage, sort, pageable));
    }

    /** 등록된 범위 수 — 탭 배지용. 0이면 배치가 모델 자동 선택으로 돈다. */
    @GetMapping("/count")
    public ApiResponse<Map<String, Long>> count() {
        return ApiResponse.ok(Map.of("count", topicQueueService.count()));
    }

    /** 범위 추가 — 맨 뒤에 붙는다. 같은 분야에 같은 범위가 이미 있으면 409(TOPIC_002). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TopicQueueItemResponse> add(@Valid @RequestBody AdminTopicQueueRequest request) {
        return ApiResponse.ok(topicQueueService.add(request));
    }

    /**
     * 범위 수정 — 분야·주제·메모만 바꾼다. 사용 기록과 순서는 그대로다.
     *
     * <p><b>PUT이 아니라 PATCH인 이유</b>: 이 요청은 행 전체를 보내지 않는다.
     * {@code lastUsedAt}·{@code usedCount}·{@code sortOrder}는 몸통에 없고 서버가 지킨다.
     * PUT으로 두면 "보낸 것이 곧 그 행"으로 읽혀, 다음에 이 API를 쓰는 사람이
     * 빠진 필드가 지워질까 봐 사용 기록까지 실어 보내게 된다.
     *
     * <p>없는 id면 404(TOPIC_001), 같은 분야에 같은 이름이 이미 있으면 409(TOPIC_002).
     * 자기 자신과 같은 이름으로 저장하는 것은 막지 않는다(메모만 고치는 경우).
     */
    @PatchMapping("/{id}")
    public ApiResponse<TopicQueueItemResponse> update(@PathVariable Long id,
                                                     @Valid @RequestBody AdminTopicQueueRequest request) {
        return ApiResponse.ok(topicQueueService.update(id, request));
    }

    /** 삭제 — 되돌릴 수 없다. 없는 id면 404(TOPIC_001). */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        topicQueueService.delete(id);
        return ApiResponse.ok();
    }

    /**
     * 순서 이동 — {@code ?direction=UP|DOWN|TOP}. UP·DOWN은 이웃과 자리를 맞바꾸고,
     * TOP은 1번으로 올리며 나머지를 한 칸씩 민다(2026-09-05).
     *
     * <p>PATCH가 아니라 POST인 이유: 이 요청은 "필드 하나를 이 값으로 고쳐라"가 아니라
     * <b>두 행의 자리를 맞바꿔라</b>는 동작이다. 어떤 값이 될지는 서버가 정한다.
     *
     * <p>TOP에 엔드포인트를 새로 만들지 않은 것은 <b>같은 동작의 정도 차이</b>이기 때문이다.
     * {@code /move-to-top}을 따로 두면 화면이 두 주소를 알아야 하고, 다음에 "맨 아래로"가
     * 생기면 셋이 된다. 방향은 이미 인자로 받고 있으니 값 하나를 늘리는 편이 맞다.
     */
    /**
     * 고른 범위들을 한꺼번에 맨 위로 — 2026-09-14 신설.
     *
     * <p>{@code /{id}/move?direction=TOP}을 여러 번 부르는 것과 <b>결과가 다르다</b>.
     * 그쪽은 한 번에 한 줄이라 나중에 부른 것이 위로 가서 순서가 뒤집힌다. 이 API는
     * 고른 것들끼리의 상대 순서를 지킨 채 덩어리째 옮긴다.
     *
     * <p>id를 쿼리스트링이 아니라 몸통으로 받는 이유: 스무 개 넘게 고르는 것이 이 기능의
     * 쓰임새인데, 그만큼이 주소에 들어가면 서버·프록시의 길이 제한에 걸릴 수 있다.
     *
     * <p>없는 id가 섞여 있어도 200이다 — 여러 쪽에 걸쳐 고르는 동안 다른 곳에서 지워졌을 수
     * 있고, 그 하나 때문에 나머지의 이동을 막을 이유가 없다.
     */
    @PostMapping("/move-top")
    public ApiResponse<Void> moveToTop(@Valid @RequestBody AdminTopicQueueMoveRequest request) {
        topicQueueService.moveToTop(request.ids());
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/move")
    public ApiResponse<Void> move(@PathVariable Long id,
                                  @RequestParam TopicQueueService.Direction direction) {
        topicQueueService.move(id, direction);
        return ApiResponse.ok();
    }

    /**
     * 사용 기록 지우기 — 그 범위를 <b>아직 안 쓴 상태</b>로 되돌린다(2026-09-05 신설).
     *
     * <p>배치는 문서를 만들기로 정한 시점에 기록을 찍는다. 그 뒤 문서가 거절되거나 지워지면
     * 실물 없이 기록만 남고, 그러면 <b>차례가 영영 안 돌아온다</b>(안 쓴 범위가 늘 우선이라).
     * 실제로 {@code 기본키와 외래키} 범위가 그 상태였다.
     *
     * <p>순서는 건드리지 않는다 — 필요하면 {@code /move?direction=TOP}을 이어서 부른다.
     * 두 동작을 하나로 묶으면 "이력만 지우기"를 할 수 없어진다.
     */
    @PostMapping("/{id}/reset-usage")
    public ApiResponse<Void> resetUsage(@PathVariable Long id) {
        topicQueueService.resetUsage(id);
        return ApiResponse.ok();
    }
}
