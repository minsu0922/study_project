package project.study.study_project.quiz.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.llm.service.DomainSettingService;
import project.study.study_project.quiz.dto.DomainResponse;

import java.util.List;

/**
 * 분야 목록 — 학습자 화면이 예전에 {@code static/js/api.js}에 하드코딩해 두던 것을
 * 서버가 대신 준다(8번 작업).
 *
 * <h2>왜 {@code quiz} 패키지인가</h2>
 *
 * <p>여기서 주는 목록은 <b>누구나</b> 보는 것이다 — 문서 목록·자유 퀴즈·오답노트 화면이
 * 로그인 전에도 분야 필터를 그려야 한다({@link PublicStatsController}와 같은 처지). 같은 값을
 * 관리자가 <b>고치는</b> API는 9번 작업에서 {@code admin} 패키지에
 * 따로 만든다 — "보는 API"와 "고치는 API"를 한 컨트롤러에 같이 두면 권한 규칙이
 * 메서드마다 갈리는 컨트롤러가 되어 {@code SecurityConfig}를 읽는 사람이 경로 하나하나의
 * 권한을 다시 따져야 한다.
 *
 * <h2>왜 꺼진 분야도 함께 주나</h2>
 *
 * <p>{@code enabled=false}는 "배치가 이 분야로 새 문제를 <b>더 만들지 않는다</b>"는 뜻일
 * 뿐, 이미 만들어진 문제가 없다는 뜻이 아니다. 꺼진 분야를 목록에서 빼면, 그 분야의
 * 기존 문제를 풀던 학습자의 화면에서 필터가 그 분야를 더 이상 말하지 못해 "내가 갖고
 * 있던 문제가 사라졌나"로 보인다. 그래서 {@link DomainSettingService#findAll()}을 그대로
 * 쓴다 — 배치 후보만 거르는 {@link DomainSettingService#batchDomains()}는 여기서 쓸 자리가
 * 아니다.
 */
@RestController
@RequiredArgsConstructor
public class DomainController {

    private final DomainSettingService domainSettingService;

    /** 화면이 부팅 시 한 번 불러 분야 필터를 채우는 자리. 순환 순서(sortOrder) 그대로 준다. */
    @GetMapping("/api/domains")
    public ApiResponse<List<DomainResponse>> domains() {
        return ApiResponse.ok(domainSettingService.findAll().stream()
                .map(DomainResponse::from)
                .toList());
    }
}
