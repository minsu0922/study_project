package project.study.study_project.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.llm.domain.GeneratedProblemDraft;
import project.study.study_project.llm.domain.ImportedDraftFile;
import project.study.study_project.llm.dto.GeneratedBatchFile;
import project.study.study_project.llm.repository.ImportedDraftFileRepository;
import project.study.study_project.llm.support.DomainCatalog;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 생성 결과 파일 한 개를 검수 대기 초안으로 흡수한다 — docs/14.
 *
 * <p>GitHub Actions가 저장소에 커밋해 둔 {@code generated/YYYY-MM-DD.json}이 여기로 들어와
 * {@code generated_problem_draft}의 PENDING 행이 된다. 그 뒤로는 관리자 검수 화면이
 * 전과 <b>완전히 똑같이</b> 동작한다 — 초안이 어디서 왔는지 화면은 알 필요가 없다.
 *
 * <p><b>파일 하나가 곧 트랜잭션 하나</b>인 이유: 초안 저장과 "흡수 완료" 기록이 반드시 함께
 * 성공하거나 함께 실패해야 한다. 둘이 갈라지면 초안만 저장되고 기록이 없어 다음 부팅에서
 * 같은 문제가 또 들어오거나(중복), 기록만 남고 초안이 없어 그날 문제가 통째로 증발한다.
 * 여러 파일을 한 트랜잭션으로 묶지 않는 이유는 반대다 — 3일 치 중 하루가 깨졌다고
 * 나머지 이틀까지 롤백되면 손해다(파일 단위 부분 성공).
 *
 * <p><b>파일을 신뢰하지 않는다</b>: 커밋된 JSON은 사람이 손으로 고칠 수 있으므로
 * {@link LlmProblemService#saveDrafts}의 규약 검증을 반드시 거친다. 관리자 버튼으로 만든
 * 문제와 같은 문을 통과하는 것이 이 설계의 핵심이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DraftImportService {

    private final LlmProblemService llmProblemService;
    private final ImportedDraftFileRepository importedFileRepository;
    private final ObjectMapper objectMapper;
    /** 분야 존재 확인용(5번 작업) — 아래 {@link #importFile} 주석의 "등록되지 않은 분야" 문단 참고. */
    private final DomainCatalog domainCatalog;

    /**
     * 파일 하나를 읽어 초안으로 저장하고 흡수 이력을 남긴다.
     *
     * <p>초안이 0건이어도 이력은 남긴다(V7 주석): 모델이 규약을 다 어겨 한 문제도 못 건진 파일을
     * 매 부팅마다 다시 읽고 다시 버리는 낭비를 막기 위해서다.
     *
     * @return 저장된 초안 수
     * @throws IOException              파일이 깨졌거나 형식이 다를 때 — 호출자가 그 파일만 건너뛴다
     * @throws IllegalArgumentException 필수 항목(분야·난이도·유형)이 비었을 때
     */
    @Transactional
    public int importFile(Path file) throws IOException {
        GeneratedBatchFile batch = objectMapper.readValue(file.toFile(), GeneratedBatchFile.class);

        // 손으로 편집됐거나 형식이 바뀐 파일을 조용히 통과시키지 않는다.
        // 여기서 예외를 던지면 이력이 남지 않아 파일을 고친 뒤 다음 부팅에 다시 시도된다(의도된 동작).
        if (batch.domain() == null || batch.difficulty() == null || batch.type() == null) {
            throw new IllegalArgumentException("분야·난이도·유형이 모두 있어야 합니다: " + file.getFileName());
        }
        // 5번 작업: 파일의 domain은 형식만 맞으면(DomainCode 값 타입) Jackson 역직렬화를 그냥
        // 통과한다 — 그 분야가 지금도 등록돼 있는지는 별개다. 배치가 도는 클라우드와 이 앱은
        // domain_setting을 공유하지 않으므로(배치는 스냅샷 파일을 읽는다, DomainCatalog 클래스
        // 주석), 배치를 돌린 시점 이후 관리자가 화면에서 그 분야를 지웠다면 이 파일은 "한때는
        // 맞았지만 지금은 아닌" 상태로 도착한다. 여기서 막지 않으면 아래 saveDrafts가 그대로
        // 저장을 시도하고, 외래키(V20)가 막아 <b>파일 전체가 IOException 없는 알 수 없는 예외로
        // 죽는다</b> — 무엇이 문제인지 로그만 봐서는 알기 어렵다.
        //
        // 위 null 검사와 같은 모양(IllegalArgumentException)으로 던지는 이유: 흡수 이력을
        // 남기지 않아야 하기 때문이다. DraftImportRunner.importAll은 파일 단위로 예외를 잡아
        // 그 파일만 건너뛰고 "들여온 것"으로 표시하지 않는다 — 관리자가 화면에서 그 분야를
        // 다시 켜거나 새로 등록하면, 다음 부팅에 이 파일이 자동으로 재시도된다. 이력을 남기면
        // 분야를 고쳐도 영영 다시 안 읽힌다(위 null 검사 주석과 같은 이유).
        if (!domainCatalog.exists(batch.domain())) {
            throw new IllegalArgumentException(
                    "등록되지 않은 분야입니다: " + batch.domain() + " (" + file.getFileName() + ")");
        }
        List<?> problems = batch.problems();
        if (problems == null || problems.isEmpty()) {
            throw new IllegalArgumentException("문제 목록이 비어 있습니다: " + file.getFileName());
        }

        // 생성 당시의 모델을 그대로 기록한다 — 현재 설정값을 쓰면 모델 교체 후 흡수한 옛 파일이
        // 새 모델 이름으로 둔갑해 승인율 비교가 오염된다. 값이 없는 파일은 정직하게 unknown.
        String model = (batch.model() == null || batch.model().isBlank()) ? "unknown" : batch.model();

        // documentSlug는 2단계에서 추가된 필드라 옛 파일에는 없다 — Jackson이 null로 채우고
        // 그대로 초안에 기록된다. "근거 문서 없이 만든 문제"와 같은 값이므로 특별 취급이 필요 없다.
        List<GeneratedProblemDraft> drafts = llmProblemService.saveDrafts(
                batch.domain(), batch.difficulty(), batch.type(), batch.problems(), model,
                batch.documentSlug());

        String filename = file.getFileName().toString();
        importedFileRepository.save(ImportedDraftFile.of(filename, drafts.size()));

        int skipped = batch.problems().size() - drafts.size();
        if (skipped > 0) {
            // 걸러진 문제가 있으면 눈에 띄게 남긴다 — 프롬프트를 손볼 신호다
            log.warn("초안 흡수: {} — {}건 저장, {}건은 규약 위반으로 제외", filename, drafts.size(), skipped);
        } else {
            log.info("초안 흡수: {} — {}건 저장 ({} × {})",
                    filename, drafts.size(), batch.domain(), batch.difficulty());
        }
        return drafts.size();
    }
}
