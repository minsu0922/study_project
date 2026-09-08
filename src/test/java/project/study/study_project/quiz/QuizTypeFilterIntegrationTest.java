package project.study.study_project.quiz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminProblemRequest;
import project.study.study_project.admin.service.AdminProblemService;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 자유 퀴즈의 유형 필터가 <b>고를 수 있는 것만</b> 보여 주는지 — 2026-09-08 신설.
 *
 * <h2>무엇을 막는 테스트인가</h2>
 *
 * <p>화면은 {@code ProblemType} 다섯 개를 늘 늘어놓았는데 DB에 있는 것은 셋뿐이었다. OX와
 * 단답형을 고르면 <b>언제나</b> "조건에 맞는 문제가 없습니다"가 떴고, 쓰는 사람에게 그것은
 * 고장과 구분되지 않는다. 이 목록을 세어서 만들기로 한 이상 두 방향으로 틀릴 수 있다:
 *
 * <ul>
 *   <li>있는 유형을 빠뜨리기 — 그 유형 문제를 아무도 찾을 수 없게 된다.
 *   <li>방금 만든 것만 담기 — 이미 있던 유형이 사라지면 필터가 매일 달라 보인다.
 * </ul>
 *
 * <p>절대값(예 "지금 셋이다")으로 단언하지 않는다. 이 목록은 <b>DB에 따라 변하는 것이 정상</b>이고,
 * 언젠가 배치가 OX를 뽑으면 넷이 된다 — 그때 이 테스트가 깨지면 고쳐야 할 것은 코드가 아니라
 * 테스트인데, 그런 테스트는 곧 아무도 안 믿게 된다. 그래서 <b>만들기 전과 후의 차이</b>를 잰다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class QuizTypeFilterIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired AdminProblemService adminProblemService;

    @Test
    @DisplayName("문제가 생긴 유형은 필터에 나타난다 — 배치가 OX를 뽑기 시작하면 저절로 돌아와야 한다")
    void listsTypesThatHaveProblems() throws Exception {
        create(ProblemType.OX, "O");

        mockMvc.perform(get("/api/quiz/types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasItem("OX")))
                // 객관식은 개발·운영 DB 어디에나 있다. 함께 나오는지까지 봐야
                // "방금 만든 것만 담는" 반대쪽 실수를 잡는다.
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasItem("MULTIPLE_CHOICE")));
    }

    /**
     * 서술형이 목록에 없다는 것은 <b>여기서 못 잰다</b> — 관리 등록이 이미 ESSAY를 막는다
     * ("서술형(ESSAY)은 자동채점 미지원이라 아직 등록할 수 없습니다"). 그래서 테스트가 서술형
     * 문제를 만들 방법이 없다. 서비스의 {@code isAutoScored} 거르기는 손으로 넣은 옛 데이터를
     * 대비한 두 번째 방어라, <b>재는 시늉만 하는 테스트</b>를 두느니 이 주석을 남긴다.
     */
    @Test
    @DisplayName("비로그인도 부를 수 있다 — 필터가 잠기면 가입 전에는 유형 칸이 통째로 사라진다")
    void openToAnonymous() throws Exception {
        // 헤더를 하나도 붙이지 않는다. 자유 퀴즈는 로그인 없이도 풀 수 있으므로
        // (AnonymousCheckIntegrationTest) 그 화면을 채우는 이 목록도 함께 열려 있어야 한다.
        mockMvc.perform(get("/api/quiz/types")).andExpect(status().isOk());
    }

    /** 유형만 다른 최소 문제 하나. 보기가 필요 없는 유형이라 answer로 채운다. */
    private void create(ProblemType type, String answer) {
        adminProblemService.create(new AdminProblemRequest(
                Domain.SECURITY, Difficulty.BEGINNER, type,
                "유형 필터 테스트",
                "유형 필터 테스트용 지문 " + UUID.randomUUID(),
                answer, "이 유형이 목록에 어떻게 반영되는지 보려고 만든 문제다.",
                List.of(), null));
    }
}
