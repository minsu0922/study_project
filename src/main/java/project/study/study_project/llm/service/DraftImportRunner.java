package project.study.study_project.llm.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import project.study.study_project.llm.repository.ImportedDraftFileRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * 앱 기동 시 아직 안 가져온 생성 결과 파일을 찾아 흡수한다 — docs/14.
 *
 * <p><b>이 클래스가 "택배함에서 물건 꺼내오기"에 해당한다.</b> GitHub Actions(택배기사)는 내 PC가
 * 꺼져 있어도 매일 저장소(택배함)에 문제를 넣어 두고, 내가 앱을 켜는 순간 여기서 한꺼번에
 * 꺼내 검수 대기함에 옮긴다. 일주일 만에 켜면 7일 치가 한 번에 들어온다 — 밀린 것이 사라지지
 * 않는다는 점이 기존 {@code @Scheduled} 배치와의 결정적 차이다(그건 그 시각에 앱이 꺼져 있으면
 * 그날 치가 영영 없어졌다).
 *
 * <p><b>왜 스캔(어떤 파일을)과 흡수(한 파일을 어떻게)를 나눴나.</b> 파일마다 트랜잭션을 따로
 * 걸어야 하는데({@link DraftImportService} 주석), 같은 클래스 안에서 자기 메서드를 호출하면
 * 스프링 프록시를 거치지 않아 {@code @Transactional}이 <b>조용히 무시된다</b>. 흔히 놓치는
 * 함정이라 아예 빈을 분리해 구조적으로 막았다.
 *
 * <p><b>한 파일이 깨져도 나머지는 들어온다</b>: 파일마다 예외를 잡아 로그만 남기고 다음으로
 * 넘어간다. 부팅 자체를 실패시키지 않는 이유는 명확하다 — 문제 생성은 부가 기능이고,
 * 이것 때문에 앱이 안 뜨면 퀴즈 풀이·복습 같은 본 기능까지 죽는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.import.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class DraftImportRunner implements ApplicationRunner {

    private final DraftImportService draftImportService;
    private final ImportedDraftFileRepository importedFileRepository;

    /** 생성 결과 파일이 쌓이는 디렉터리(저장소 루트 기준). Actions가 커밋하는 위치와 같아야 한다. */
    @Value("${llm.import.dir:generated}")
    private String importDir;

    @Override
    public void run(ApplicationArguments args) {
        Path dir = Path.of(importDir);
        if (!Files.isDirectory(dir)) {
            // 디렉터리가 없는 건 정상 상황이다(아직 배치가 한 번도 안 돌았거나 다른 위치에서 실행 중).
            // 경고로 남기면 매 부팅마다 시끄러워서 무시하게 되므로 debug로 둔다.
            log.debug("생성 결과 디렉터리 없음, 흡수 건너뜀: {}", dir.toAbsolutePath());
            return;
        }

        List<Path> files;
        try (var stream = Files.list(dir)) {
            files = stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    // '_'로 시작하는 파일은 배치 결과가 아니다(_existing-questions.json 등 보조 파일)
                    .filter(p -> !p.getFileName().toString().startsWith("_"))
                    // 날짜 파일명이라 이름 오름차순 = 오래된 순. 검수 화면이 오래된 순으로 보여 주므로
                    // 저장 순서도 맞춰 두면 "먼저 만들어진 문제가 먼저 검수된다"가 자연스럽게 성립한다.
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (Exception e) {
            log.warn("생성 결과 디렉터리를 읽지 못했습니다: {}", e.getMessage());
            return;
        }

        int importedFiles = 0;
        int importedDrafts = 0;
        for (Path file : files) {
            String filename = file.getFileName().toString();
            if (importedFileRepository.existsById(filename)) {
                continue; // 이미 가져온 파일 — 조용히 통과(대부분의 부팅에서 여기로 빠진다)
            }
            try {
                importedDrafts += draftImportService.importFile(file);
                importedFiles++;
            } catch (Exception e) {
                // 파일 하나의 문제로 나머지를 막지 않는다. 이력을 남기지 않았으므로,
                // 파일을 고치면 다음 부팅에 자동으로 다시 시도된다.
                log.error("초안 흡수 실패(이 파일만 건너뜁니다): {} — {}", filename, e.getMessage());
            }
        }

        if (importedFiles > 0) {
            log.info("생성 결과 흡수 완료: 파일 {}개에서 초안 {}건 — 관리자 화면에서 검수하세요",
                    importedFiles, importedDrafts);
        }
    }
}
