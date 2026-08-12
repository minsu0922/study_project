package project.study.study_project.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.dto.ExistingQuestionsFile;
import project.study.study_project.quiz.repository.ProblemRepository;

import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p>구조와 판단은 형제들과 같다: 앱이 직접 {@code git push}하지 않고(권한·인증·충돌이
 * 줄줄이 따라온다), <b>바뀐 게 있을 때만</b> 파일을 쓰고 info 로그로 알린다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.import.enabled", havingValue = "true", matchIfMissing = true)
@Order(40) // 흡수(@Order 10)보다 뒤 — 승인 여부와 무관하지만 로그가 "받고 → 내보내고"로 읽힌다
@RequiredArgsConstructor
public class ExistingQuestionsExporter implements ApplicationRunner {

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

    private final ProblemRepository problemRepository;
    private final ObjectMapper objectMapper;

    /** 생성 결과 파일과 같은 디렉터리를 쓴다 — Actions가 보는 곳이 한 군데여야 한다. */
    @Value("${llm.import.dir:generated}")
    private String exportDir;

    @Override
    public void run(ApplicationArguments args) {
        try {
            export(Path.of(exportDir));
        } catch (Exception e) {
            // 이 기능이 실패해도 앱은 정상이어야 한다 — 중복 회피는 부가 기능이고,
            // 이것 때문에 부팅이 막히면 퀴즈 풀이 같은 본 기능까지 죽는다.
            log.warn("기존 지문 스냅샷 내보내기 실패(무시하고 계속): {}", e.getMessage());
        }
    }

    /** 디렉터리를 받아 스냅샷을 갱신한다. 실제로 파일을 썼으면 true. */
    boolean export(Path dir) throws Exception {
        List<ExistingQuestionsFile.Item> questions = collectQuestions();

        if (questions.isEmpty()) {
            // 정식 문제가 아직 하나도 없다(방금 초기화한 DB가 그렇다). 빈 파일을 새로 만들면
            // 커밋할 것도 없는데 변경만 생긴다 — 이미 있는 파일은 아래 hasChanged가 처리한다.
            if (!Files.exists(dir.resolve(FILE_NAME))) {
                log.debug("정식 문제가 없어 지문 스냅샷을 만들지 않습니다.");
                return false;
            }
        }

        Path file = dir.resolve(FILE_NAME);
        if (!hasChanged(file, questions)) {
            log.debug("기존 지문 스냅샷 변경 없음: {}건", questions.size());
            return false;
        }

        ExistingQuestionsFile snapshot = new ExistingQuestionsFile(
                "정식 problem 테이블의 기존 지문 목록입니다. GitHub Actions 생성 배치가 중복 회피 목록으로 "
                        + "읽습니다(클라우드에는 DB가 없으므로). docs/14 참고. "
                        + "앱이 기동할 때 자동 갱신되며, 커밋해야 다음 배치부터 반영됩니다.",
                LocalDate.now().toString(), questions);

        Files.createDirectories(dir);
        Files.writeString(file, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));

        log.info("기존 지문 스냅샷 갱신: {} ({}건) — 커밋하면 다음 배치부터 반영됩니다", file, questions.size());
        return true;
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

    /**
     * 기존 파일과 지문 목록이 다른지 본다. 파일이 없으면 당연히 "바뀐 것".
     *
     * <p>{@code exportedAt}은 비교하지 않는다 — 날이 바뀔 때마다 내용이 같아도 파일을 새로 쓰게 되고,
     * 그러면 <b>앱을 켤 때마다 git이 변경으로 인식</b>해 진짜 바뀐 날을 알아볼 수 없다
     * (형제 내보내기들과 같은 규칙).
     */
    private boolean hasChanged(Path file, List<ExistingQuestionsFile.Item> questions) {
        if (!Files.exists(file)) {
            return true;
        }
        try {
            ExistingQuestionsFile existing = objectMapper.readValue(file.toFile(), ExistingQuestionsFile.class);
            List<ExistingQuestionsFile.Item> before =
                    existing.questions() == null ? List.of() : existing.questions();
            return !questions.equals(before);
        } catch (Exception e) {
            // 파일이 깨졌거나 형식이 바뀐 경우 — 새로 쓰는 쪽이 안전하다
            log.debug("기존 스냅샷을 읽지 못해 새로 씁니다: {}", e.getMessage());
            return true;
        }
    }
}
