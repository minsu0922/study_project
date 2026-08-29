package project.study.study_project.admin.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
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
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.ok(topicQueueService.search(q, pageable));
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

    /** 삭제 — 되돌릴 수 없다. 없는 id면 404(TOPIC_001). */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        topicQueueService.delete(id);
        return ApiResponse.ok();
    }

    /**
     * 순서 이동 — {@code ?direction=UP|DOWN}. 이웃과 자리를 맞바꾼다.
     *
     * <p>PATCH가 아니라 POST인 이유: 이 요청은 "필드 하나를 이 값으로 고쳐라"가 아니라
     * <b>두 행의 자리를 맞바꿔라</b>는 동작이다. 어떤 값이 될지는 서버가 정한다.
     */
    @PostMapping("/{id}/move")
    public ApiResponse<Void> move(@PathVariable Long id,
                                  @RequestParam TopicQueueService.Direction direction) {
        topicQueueService.move(id, direction);
        return ApiResponse.ok();
    }
}
