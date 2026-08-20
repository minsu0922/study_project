package project.study.study_project.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.dto.ExistingQuestionsFile;
import project.study.study_project.quiz.repository.ProblemRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 정식 문제의 지문을 스냅샷 파일로 내보낸다 — 중복 출제를 막는 마지막 연결선(docs/14).
 *
 * <p><b>왜 뒤늦게 생겼나.</b> 형제 격인 {@link RejectionNotesExporter},
 * {@link ExistingDocumentsExporter}는 처음부터 있었는데 이것만 없었다. 그동안
 * {@code _existing-questions.json}은 <b>손으로 만든 파일</b>이었고, 아무도 갱신하지 않았다.
 * 시드 마이그레이션을 걷어낼 때 그 파일에 <b>이미 삭제된 문제 24건</b>이 그대로 남아 있는 것을
 * 보고 드러났다 — 없는 문제를 피하라고 하면 그 주제로 새 문제를 영영 못 만든다.
 *
 * <p>파일을 언제 쓰고 언제 안 쓰는지 같은 공통 규칙은 {@link SnapshotExporter}에 있다.
 * 여기 남은 것은 "무엇을 모아 어떤 모양으로 쓰는가"뿐이다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.import.enabled", havingValue = "true", matchIfMissing = true)
@Order(40) // 흡수(@Order 10)보다 뒤 — 승인 여부와 무관하지만 로그가 "받고 → 내보내고"로 읽힌다
public class ExistingQuestionsExporter extends SnapshotExporter {

    /** 배치({@code DraftGeneratorCli})가 읽는 파일명. {@code _} 접두사라 흡수 대상과 섞이지 않는다. */
    static final String FILE_NAME = "_existing-questions.json";

    /**
     * 분야당 내보낼 지문 수 상한.
     *
     * <p>CLI가 프롬프트에 넣는 상한(그쪽 {@code AVOID_LIST_SIZE})과 같은 값이지만 <b>목적이 다르다</b>:
     * 저쪽은 입력 토큰 비용을 막고, 이쪽은 저장소에 커밋되는 파일이 무한정 커지는 것을 막는다.
     * 상수를 공유하지 않은 이유가 그것이다 — 나중에 한쪽만 바꿀 이유가 충분히 생긴다.
     * 다만 이 값이 CLI 상한보다 <b>작아지면</b> 회피 목록이 조용히 짧아지므로 줄일 때는 함께 본다.
     */
    static final int PER_DOMAIN_LIMIT = 50;

    private static final String NOTE =
            "정식 problem 테이블의 기존 지문 목록입니다. GitHub Actions 생성 배치가 중복 회피 목록으로 "
                    + "읽습니다(클라우드에는 DB가 없으므로). docs/14 참고. "
                    + "앱이 기동할 때 자동 갱신되며, 커밋해야 다음 배치부터 반영됩니다.";

    private final ProblemRepository problemRepository;

    public ExistingQuestionsExporter(ProblemRepository problemRepository, ObjectMapper objectMapper) {
        super(objectMapper);
        this.problemRepository = problemRepository;
    }

    @Override
    protected String fileName() {
        return FILE_NAME;
    }

    @Override
    protected String label() {
        return "기존 지문 스냅샷";
    }

    /**
     * <p>정식 문제가 하나도 없는데 <b>파일도 없으면</b> 만들지 않는다(방금 초기화한 DB가 그렇다).
     * 커밋할 것도 없는데 빈 파일만 생기기 때문. 반대로 <b>파일이 이미 있으면</b> 빈 목록으로라도
     * 갱신한다 — 그래야 지워진 문제가 회피 목록에 남아 그 주제를 영영 막는 일이 없다.
     */
    @Override
    protected Snapshot build(boolean fileExists) {
        List<ExistingQuestionsFile.Item> questions = collectQuestions();
        if (questions.isEmpty() && !fileExists) {
            return null;
        }
        return new Snapshot(
                new ExistingQuestionsFile(NOTE, LocalDate.now().toString(), questions),
                questions.size() + "건");
    }

    /**
     * 검수가 <b>커밋된 뒤</b> 스냅샷을 다시 찍는다 — 자세한 배경은 {@link ReviewCompleted}.
     *
     * <p>PROBLEM 외의 신호는 흘려보낸다. 바뀔 리 없는 파일을 다시 읽고 비교하는 비용은
     * 작지만, 로그에 "변경 없음"이 두 배로 쌓여 진짜 갱신이 묻힌다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewCompleted(ReviewCompleted event) {
        if (event.target() != ReviewCompleted.Target.PROBLEM) {
            return;
        }
        exportQuietly("검수는 정상 처리됨");
    }

    /**
     * 분야별로 최신 {@link #PER_DOMAIN_LIMIT}건까지 모은다.
     *
     * <p>전체를 한 번에 읽고 메모리에서 나누는 이유: 분야별로 조회하면 8번 왕복이고,
     * JPQL에는 "그룹별 상위 N" 문법이 없어 어차피 손으로 잘라야 한다. 부팅당 1회이고
     * 문제 수가 수백 건 규모라 통째로 읽어도 부담이 없다.
     *
     * <p>저장 순서는 조회 순서(id 역순)를 그대로 따른다 — 분야로 다시 묶어 정렬하면
     * 같은 내용인데도 파일이 바뀐 것처럼 보일 수 있다.
     */
    private List<ExistingQuestionsFile.Item> collectQuestions() {
        Map<Domain, Integer> countPerDomain = new EnumMap<>(Domain.class);
        List<ExistingQuestionsFile.Item> result = new ArrayList<>();

        for (ProblemRepository.DomainQuestion row : problemRepository.findAllDomainQuestions()) {
            int used = countPerDomain.getOrDefault(row.getDomain(), 0);
            if (used >= PER_DOMAIN_LIMIT) {
                continue;
            }
            countPerDomain.put(row.getDomain(), used + 1);
            result.add(new ExistingQuestionsFile.Item(row.getDomain(), row.getQuestion()));
        }
        return result;
    }
}
