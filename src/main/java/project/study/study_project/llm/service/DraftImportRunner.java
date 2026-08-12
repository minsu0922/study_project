package project.study.study_project.llm.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
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
 *
 * <p><b>문제와 문서를 한 러너가 처리하는 이유</b>(docs/15): 흡수 규칙 — 이미 가져온 파일은
 * 건너뛰기, 오래된 순 정렬, 한 파일 실패가 나머지를 막지 않기 — 이 셋이 완전히 같다.
 * 러너를 둘로 나누면 이 규칙이 복사되고, 나중에 한쪽만 고쳐져 <b>문서만 중복 흡수되는</b>
 * 같은 종류의 버그가 생긴다. 파일을 읽고 해석하는 부분만 서비스 둘로 갈라져 있다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.import.enabled", havingValue = "true", matchIfMissing = true)
/*
 * 스냅샷 내보내기(@Order 20·30·40)보다 <b>먼저</b> 돌아야 한다 — 방금 들어온 초안이
 * 그날의 스냅샷에 반영되게 하려면 흡수가 앞서야 하기 때문.
 *
 * [왜 명시적으로 붙였나 — 실제로 반대로 돌고 있었다]
 * 순서를 안 붙이면 Spring은 그 러너를 Ordered.LOWEST_PRECEDENCE로 보고 <b>맨 마지막</b>에
 * 돌린다. 즉 지금까지 "흡수 → 내보내기"라고 적어 둔 주석과 정반대로 "내보내기 → 흡수"가
 * 실행되고 있었다. 증상이 조용해서 오래 안 드러났다: 흡수가 실제로 일어나는 부팅
 * (파일이 새로 생긴 날)에만 한 박자 어긋나고, 그 다음 부팅에는 이미 흡수돼 있어
 * 결과가 같아 보인다. "하루 늦게 반영되는" 종류의 어긋남이라 눈치채기 어렵다.
 */
@Order(10)
@RequiredArgsConstructor
public class DraftImportRunner implements ApplicationRunner {

    private final DraftImportService draftImportService;
    private final DocumentImportService documentImportService;
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

        // 문제: generated/*.json — 파일명이 곧 이력의 열쇠(V7 이후 그대로 유지해야 한다.
        // 열쇠 규칙을 바꾸면 이미 흡수한 파일이 "처음 보는 파일"이 되어 전부 다시 들어온다)
        Result problems = importAll(scan(dir), draftImportService::importFile, name -> name);

        // 문서: generated/documents/*.json — 열쇠에 폴더를 붙인다(DocumentImportService.importKey 주석).
        // 문제 파일과 이름이 같아서, 파일명만 쓰면 문서가 통째로 건너뛰어진다.
        Result documents = importAll(scan(dir.resolve(DocumentImportService.DOCUMENT_SUBDIR)),
                documentImportService::importFile, DocumentImportService::importKey);

        int files = problems.files() + documents.files();
        if (files > 0) {
            log.info("생성 결과 흡수 완료: 파일 {}개에서 문제 초안 {}건 + 문서 초안 {}건 — 관리자 화면에서 검수하세요",
                    files, problems.drafts(), documents.drafts());
        }
    }

    /**
     * 디렉터리에서 흡수 대상 파일을 골라 오래된 순으로 정렬한다. 없거나 못 읽으면 빈 목록.
     *
     * <p>{@code Files.list()}는 하위 디렉터리를 파고들지 않는다 — 그래서 {@code generated/}를
     * 훑을 때 {@code documents/} 폴더는 {@code .json} 필터에서 자연스럽게 걸러진다.
     * 문서를 접두사가 아니라 <b>폴더</b>로 분리한 것이 여기서 값을 한다(docs/15).
     */
    private List<Path> scan(Path dir) {
        if (!Files.isDirectory(dir)) {
            // 문서 폴더는 배치가 문서를 한 번도 안 만들었으면 없는 게 정상이다.
            log.debug("생성 결과 디렉터리 없음, 건너뜀: {}", dir.toAbsolutePath());
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    // '_'로 시작하는 파일은 배치 결과가 아니다(_existing-questions.json 등 보조 파일)
                    .filter(p -> !p.getFileName().toString().startsWith("_"))
                    // 날짜 파일명이라 이름 오름차순 = 오래된 순. 검수 화면이 오래된 순으로 보여 주므로
                    // 저장 순서도 맞춰 두면 "먼저 만들어진 문제가 먼저 검수된다"가 자연스럽게 성립한다.
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (Exception e) {
            log.warn("생성 결과 디렉터리를 읽지 못했습니다({}): {}", dir, e.getMessage());
            return List.of();
        }
    }

    /**
     * 파일 목록을 차례로 흡수한다 — 문제와 문서가 공유하는 유일한 반복문.
     *
     * @param importer 파일 하나를 흡수하고 저장 건수를 돌려주는 동작(서비스마다 다름)
     * @param keyOf    파일명 → 흡수 이력의 열쇠(문제는 그대로, 문서는 폴더를 붙임)
     */
    private Result importAll(List<Path> files, FileImporter importer,
                             java.util.function.UnaryOperator<String> keyOf) {
        int importedFiles = 0;
        int importedDrafts = 0;
        for (Path file : files) {
            String filename = file.getFileName().toString();
            if (importedFileRepository.existsById(keyOf.apply(filename))) {
                continue; // 이미 가져온 파일 — 조용히 통과(대부분의 부팅에서 여기로 빠진다)
            }
            try {
                importedDrafts += importer.importFile(file);
                importedFiles++;
            } catch (Exception e) {
                // 파일 하나의 문제로 나머지를 막지 않는다. 이력을 남기지 않았으므로,
                // 파일을 고치면 다음 부팅에 자동으로 다시 시도된다.
                log.error("초안 흡수 실패(이 파일만 건너뜁니다): {} — {}", filename, e.getMessage());
            }
        }
        return new Result(importedFiles, importedDrafts);
    }

    /**
     * 흡수 동작 — {@code IOException}을 던지므로 {@code Function}으로는 표현할 수 없어 따로 둔다.
     * 두 서비스의 {@code importFile}이 우연히 같은 모양인 게 아니라, 같은 계약을 지키게 하려는 것.
     */
    @FunctionalInterface
    private interface FileImporter {
        int importFile(Path file) throws Exception;
    }

    private record Result(int files, int drafts) {
    }
}
