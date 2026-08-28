package project.study.study_project.llm.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.llm.client.ClaudeTitleGenerator;
import project.study.study_project.llm.client.GeneratedTitle;
import project.study.study_project.llm.client.TitleGenerator;
import project.study.study_project.llm.dto.TitleBackfillResponse;
import project.study.study_project.llm.support.ProblemItemRule;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 제목이 없는 기존 문제에 목록 제목을 채운다 — 제목 컬럼(V13)의 뒤처리.
 *
 * <p><b>왜 Flyway 마이그레이션이 아닌가.</b> 이 저장소는 <b>스키마만 마이그레이션으로 넣는다</b>
 * (2026-08-12에 콘텐츠 시드 V2·V2_1·V3를 걷어내며 정한 규칙). 제목은 콘텐츠다. 게다가
 * 마이그레이션에 33건의 UPDATE를 박으면 그 문장들이 저장소에 영원히 남는데, 정작 그 문제들이
 * 지워지고 나면 아무 데도 안 쓰이는 SQL 33줄이 남는다.
 *
 * <p><b>왜 일회용 스크립트가 아닌가.</b> 한 번 돌리고 지우면 다음에 제목 없는 문제가 생겼을 때
 * 아무 장치도 없다. 그리고 생긴다 — 관리자 등록 폼의 제목 칸은 선택이고, 모델도 가끔 빈 값을
 * 낸다({@code ProblemItemRule}이 경고하는 그 경우). 관리 화면 버튼으로 남겨 두면
 * 목록에 "(제목 없음)"이 보일 때마다 누르면 된다.
 *
 * <h2>이 클래스가 실제로 하는 일은 "모델을 믿지 않는 것"이다</h2>
 *
 * <p>부르는 것 자체는 한 줄이고, 나머지는 전부 응답을 검사하는 코드다:
 * <ul>
 *   <li><b>짝짓기는 id로</b> — 순서로 짝지으면 모델이 한 건을 빠뜨린 순간 전부 한 칸씩 밀리고,
 *       그 상태는 오류를 내지 않는다({@link GeneratedTitle} 주석)
 *   <li><b>모르는 id는 버린다</b> — 요청하지 않은 문제에 제목이 붙는 것을 막는다
 *   <li><b>덮어쓰지 않는다</b> — 판단은 엔티티에 맡긴다({@link Problem#fillTitleIfAbsent})
 *   <li><b>너무 긴 제목은 자른다</b> — 컬럼이 120자라 넘치면 저장이 <b>실패</b>한다.
 *       한 건 때문에 백필 전체가 롤백되는 것보다 잘라 넣고 검수자가 다듬는 편이 낫다
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProblemTitleBackfillService {

    /**
     * DB 컬럼 상한(V13의 VARCHAR(120))에 맞춘 자르기 기준.
     *
     * <p>품질 기준인 {@link ProblemItemRule#TITLE_MAX}(40자)와 다른 값인 것은 의도다.
     * 40자를 넘겼다고 자르면 <b>지시를 조금 넘긴 멀쩡한 제목이 말줄임표로 잘려</b> 들어간다.
     * 여기서 막는 것은 품질이 아니라 <b>저장 실패</b>다 — 잘림은 컬럼을 넘길 때만 일어나야 한다.
     */
    private static final int COLUMN_MAX = 120;

    private final ProblemRepository problemRepository;
    private final TitleGenerator titleGenerator;

    /**
     * 제목 없는 문제를 최대 {@link ClaudeTitleGenerator#BATCH_SIZE}건 골라 제목을 채운다.
     *
     * <p><b>한 번에 다 끝내지 않는다.</b> 남은 건수를 함께 돌려주므로 화면이 "또 남았다"를
     * 보여 주고 사람이 한 번 더 누르면 된다. 서버가 알아서 반복하지 않는 이유는 그 편이
     * 안전하기 때문이다 — 프롬프트가 잘못돼 이상한 제목이 나오고 있다면, 33건을 다 망친 뒤에
     * 아는 것보다 40건에서 멈추고 눈으로 확인하는 쪽이 낫다.
     *
     * <p><b>트랜잭션을 Claude 호출 <em>뒤</em>로 미루지 않았다.</b> {@code @Transactional}이
     * 메서드 전체에 걸려 있어 API를 기다리는 동안 커넥션을 물고 있는데, 이는
     * {@code LlmProblemService.generate}가 피한 바로 그것이다. 여기서 허용한 이유는
     * <b>변경 감지로 저장하기 때문</b>이다 — 조회한 엔티티가 영속 상태로 살아 있어야 제목을
     * 써 넣는 것만으로 UPDATE가 나간다. 트랜잭션을 쪼개면 id로 다시 조회해야 한다.
     * 관리자가 어쩌다 한 번 누르는 버튼이고 동시 요청이 없으므로, 커넥션 하나를 수십 초
     * 물고 있는 값을 치를 만하다. (매일 도는 배치였다면 반대로 판단했을 것이다.)
     */
    @Transactional
    public TitleBackfillResponse backfill() {
        List<Problem> targets = problemRepository.findWithoutTitle(
                PageRequest.of(0, ClaudeTitleGenerator.BATCH_SIZE));
        if (targets.isEmpty()) {
            return new TitleBackfillResponse(0, 0, 0, List.of());
        }

        List<TitleGenerator.UntitledProblem> request = targets.stream()
                .map(p -> new TitleGenerator.UntitledProblem(p.getId(), p.getQuestion()))
                .toList();
        List<GeneratedTitle> generated = titleGenerator.generateTitles(request);

        // id → 제목. 모델이 같은 id를 두 번 냈으면 <먼저 온 것>을 쓴다. 뒤엣것으로 덮으면
        // 어느 쪽이 쓰였는지가 응답 순서에 달려 매번 달라진다 — 재현되지 않는 결과가 가장 나쁘다.
        Map<Long, String> titleById = generated.stream()
                .filter(t -> t.title() != null && !t.title().isBlank())
                .collect(Collectors.toMap(GeneratedTitle::problemId, GeneratedTitle::title,
                        (first, duplicate) -> first));

        List<TitleBackfillResponse.Filled> filled = new ArrayList<>();
        for (Problem problem : targets) {
            String title = titleById.get(problem.getId());
            if (title == null) {
                // 모델이 빠뜨린 건. 제목이 여전히 NULL이라 다음 실행이 다시 집어 온다 —
                // 여기서 재시도하지 않아도 손실이 없는 구조다.
                continue;
            }
            if (problem.fillTitleIfAbsent(truncate(title))) { // 변경 감지로 커밋 시 UPDATE
                filled.add(new TitleBackfillResponse.Filled(problem.getId(), problem.getTitle()));
            }
        }

        // 남은 건수는 <다시 세기만> 한다. 빼지 않는다.
        //
        // 2026-08-28 수정. 원래는 "커밋 전이라 방금 채운 것이 그대로 세어진다"고 보고
        // filled.size()를 뺐는데, 그 전제가 틀렸다. JPQL 조회는 <실행 전에 자동으로 flush한다> —
        // 보류 중인 변경이 조회 대상 테이블과 겹치면 Hibernate가 UPDATE를 먼저 내보낸다.
        // 그래서 이 count는 이미 채운 것을 뺀 값이고, 거기서 또 빼면 두 번 빠진다.
        //
        // 여기서는 티가 안 났다 — 제목 백필은 대상 33건을 한두 번에 끝냈고, 마지막 실행에서는
        // 양쪽 다 0에 가까워 음수가 눈에 띄지 않았다. 오답 설명 채우기(V15)를 같은 모양으로
        // 만들었더니 26건을 세 번에 나눠 돌리면서 remaining이 -4로 나와 드러났다.
        // 여기가 원본이므로 같이 고친다.
        long remaining = problemRepository.countByTitleIsNull();
        log.info("제목 백필: 대상 {}건, 모델 응답 {}건, 채움 {}건, 남음 {}건",
                targets.size(), generated.size(), filled.size(), remaining);
        return new TitleBackfillResponse(targets.size(), filled.size(), remaining, filled);
    }

    /** 관리 화면이 버튼을 보여 줄지 정하는 데 쓴다 — 0건이면 할 일이 없다. */
    @Transactional(readOnly = true)
    public long untitledCount() {
        return problemRepository.countByTitleIsNull();
    }

    /** 컬럼 상한을 넘기면 자른다 — 한 건 때문에 백필 전체가 롤백되는 것을 막는다(상수 주석 참고). */
    private String truncate(String title) {
        String trimmed = title.trim();
        if (trimmed.length() <= COLUMN_MAX) {
            return trimmed;
        }
        log.warn("제목이 컬럼 상한을 넘어 자른다: {}자 → {}자", trimmed.length(), COLUMN_MAX);
        return trimmed.substring(0, COLUMN_MAX - 1) + "…";
    }
}
