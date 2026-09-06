package project.study.study_project.quiz.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.quiz.dto.PublicStatsResponse;
import project.study.study_project.quiz.repository.ProblemRepository;

/**
 * 랜딩용 공개 집계 — <b>인증이 필요 없는 유일한 통계 엔드포인트</b> (2026-09-06).
 *
 * <h2>왜 서비스 계층이 없나</h2>
 *
 * <p>하는 일이 리포지토리 {@code count()} 둘과 enum 길이 하나를 담는 것뿐이다. 도메인
 * 규칙이 하나도 없는데 계층을 만들면 <b>통과만 하는 파일</b>이 는다 — 읽는 사람이
 * "여기 뭔가 있나" 하고 열어 봤다가 아무것도 없는 것을 확인하는 비용만 남는다.
 * 규칙(예: "최근 30일에 추가된 것만 센다")이 생기면 그때 서비스로 내린다.
 *
 * <h2>왜 캐시가 없나</h2>
 *
 * <p>랜딩을 여는 사람만 한 번 부르는 값이고, 두 집계 모두 인덱스 없이도 즉시 끝나는
 * {@code count(*)}다. 캐시를 두면 "배치가 문제를 더했는데 숫자가 안 바뀐다"는 상태가
 * 생기는데, 이 API의 존재 이유가 바로 <b>숫자가 낡지 않게</b> 하는 것이다.
 * 트래픽이 늘어 문제가 되면 그때 캐시를 두되 TTL을 배치 주기보다 짧게 잡는다.
 *
 * <p><b>요청 제한</b>은 api 버킷(60/분)을 그대로 탄다 — 공개 API라고 예외를 두지 않는다.
 */
@RestController
@RequiredArgsConstructor
public class PublicStatsController {

    private final ProblemRepository problemRepository;
    private final DocumentRepository documentRepository;

    /** 랜딩 화면이 화면을 열 때 한 번 부른다. */
    @GetMapping("/api/stats")
    public ApiResponse<PublicStatsResponse> stats() {
        return ApiResponse.ok(new PublicStatsResponse(
                problemRepository.count(),
                documentRepository.count(),
                Domain.values().length));
    }
}
