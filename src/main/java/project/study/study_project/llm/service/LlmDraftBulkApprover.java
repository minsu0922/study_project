package project.study.study_project.llm.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import project.study.study_project.admin.dto.AdminProblemDetail;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.llm.dto.LlmBulkApproveResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * 문제 초안 <b>일괄 승인</b> — 체크한 여러 건을 한 요청으로 승인한다.
 *
 * <h2>왜 {@link LlmProblemService} 안이 아니라 별도 클래스인가</h2>
 *
 * <p>이 클래스의 존재 이유는 딱 하나, <b>승인 한 건마다 트랜잭션을 끊기 위해서</b>다.
 * Spring의 {@code @Transactional}은 프록시(대리인)가 걸어 준다 — 밖에서 빈을 부르면 대리인을
 * 거치지만, <b>같은 클래스 안에서 자기 메서드를 부르면 대리인을 건너뛴다</b>. 그래서 반복문을
 * {@code LlmProblemService} 안에 두면 {@code approve()}의 {@code @Transactional}이 조용히
 * 무시되고, 50건이 트랜잭션 하나에 묶인다. 밖에서 부르는 이 클래스에서는 매 호출이 대리인을
 * 지나므로 건마다 독립적으로 커밋된다.
 *
 * <h2>왜 한 트랜잭션으로 묶으면 안 되나 — 실패를 잡아도 전부 되돌아간다</h2>
 *
 * <p>"한 트랜잭션 안에서 실패만 {@code catch}하고 계속하면 되지 않나"가 자연스러운 발상인데,
 * 그렇게 하면 <b>겉으로만</b> 성공한다. {@code approve()}가 부르는
 * {@code AdminProblemService.create}도 트랜잭션 경계라, 거기서 예외가 빠져나오는 순간 바깥
 * 트랜잭션에 "이건 롤백 대상" 표시(rollback-only)가 찍힌다. 우리가 예외를 삼켜도 표시는 남아,
 * 마지막 커밋 시도에서 {@code UnexpectedRollbackException}과 함께 <b>성공했던 건까지 전부</b>
 * 사라진다. 즉 "9건 승인, 1건 실패"가 아니라 "0건 승인"이 된다.
 *
 * <p>그래서 건별 트랜잭션 + 부분 성공을 택했다. 초안 저장의 "5문제 중 1개가 이상하다고 나머지
 * 4개를 버리지 않는다"({@link LlmProblemService#saveDrafts})와 같은 원칙이고, 검수라는 일의
 * 성격에도 맞는다 — 승인 열 건은 서로 아무 관계가 없는 열 개의 결정이다.
 *
 * <h2>버린 대안</h2>
 *
 * <p>자기 자신을 {@code ObjectProvider<LlmProblemService>}로 주입받아 대리인을 우회하는
 * 방법(self-injection)도 있다. 클래스는 안 늘지만 "왜 나를 나에게 주입하지?"를 읽는 사람이
 * 매번 되짚어야 하고, {@code LlmProblemService}의 생성자가 한 칸 더 길어져 이미 그 생성자를
 * 직접 부르고 있는 테스트 두 곳이 함께 흔들린다. 클래스 하나 느는 쪽이 싸다고 봤다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmDraftBulkApprover {

    private final LlmProblemService llmProblemService;

    /**
     * 초안들을 차례로 승인하고 건별 결과를 모아 돌려준다.
     *
     * <p><b>이 메서드에는 {@code @Transactional}이 없다.</b> 붙이는 순간 위에서 설명한
     * "전부 되돌아감"이 그대로 재현된다 — 여기에 트랜잭션이 열려 있으면 아래 개별 승인이
     * 새 트랜잭션을 열지 않고 이 트랜잭션에 합류하기 때문이다(REQUIRED의 기본 동작).
     *
     * <p>순서는 받은 그대로다. 화면이 보낸 순서 = 사람이 화면에서 본 순서라, 결과를 위에서부터
     * 짚어 보기 쉽다. 정렬해서 얻을 이득이 없다.
     *
     * <p>실패는 {@link BusinessException}만 결과로 담는다. 그 외 예외(DB 장애 등)는 삼키지 않고
     * 그대로 터뜨린다 — "이 초안이 규칙에 안 맞는다"와 "시스템이 고장 났다"는 사람이 할 행동이
     * 다르다. 앞은 그 건만 손보면 되지만, 뒤는 나머지 승인 시도도 어차피 실패한다.
     */
    public LlmBulkApproveResponse approveAll(List<Long> draftIds) {
        List<LlmBulkApproveResponse.Approved> approved = new ArrayList<>();
        List<LlmBulkApproveResponse.Failed> failed = new ArrayList<>();

        for (Long id : draftIds) {
            try {
                // 대리인을 거치는 호출 — 이 한 줄이 곧 트랜잭션 하나다(위 주석의 핵심)
                AdminProblemDetail created = llmProblemService.approve(id);
                approved.add(new LlmBulkApproveResponse.Approved(id, created.id()));
            } catch (BusinessException e) {
                // 이미 승인·거절된 초안(LLM_002), 없는 초안(LLM_001), 규칙 위반(QUIZ_004) 등.
                // 다음 건으로 넘어간다 — 서로 독립된 결정이므로 하나가 막는 것이 이상하다.
                log.info("일괄 승인 건너뜀: 초안 #{} — {}", id, e.getMessage());
                failed.add(new LlmBulkApproveResponse.Failed(id, e.getMessage()));
            }
        }

        log.info("일괄 승인 완료: 요청 {}건 → 성공 {}건, 실패 {}건",
                draftIds.size(), approved.size(), failed.size());
        return new LlmBulkApproveResponse(approved, failed);
    }
}

