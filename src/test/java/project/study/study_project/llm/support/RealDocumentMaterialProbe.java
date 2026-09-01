package project.study.study_project.llm.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.dto.GeneratedDocumentFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 저장소에 실제로 있는 개념 문서에 규칙을 걸어 본다 — 합성 문서만으로는 안 보이는 것을 잡는다.
 *
 * <p><b>왜 필요한가.</b> {@code TypeMaterialRuleTest}가 쓰는 문서는 내가 손으로 지은 것이라,
 * 내가 상상한 모양만 검사한다. 실제 문서는 표 앞에 빈 줄이 없거나, 절 제목 뒤에 인용문이 붙거나,
 * 코드블록 안에 파이프가 있다 — 그런 것들이 규칙을 조용히 빗나가게 한다.
 *
 * <p><b>왜 개수를 단언하지 않는가.</b> {@code generated/documents/}는 배치가 계속 채우는 자리라
 * "8편 통과"를 박아 두면 문서가 하나 늘 때마다 무관한 테스트가 깨진다. 대신 <b>규칙이 실제로
 * 갈라 놓는지</b>만 본다 — 전부 통과하거나 전부 막히면 그건 규칙이 아니라 상수다.
 *
 * <p>파일이 없는 환경(CI에서 문서를 안 받은 경우 등)에서는 조용히 건너뛴다. 이 테스트가 지키려는
 * 것은 규칙의 동작이지 저장소의 내용물이 아니다.
 */
class RealDocumentMaterialProbe {

    private static final Path DOC_DIR = Path.of("generated", "documents");

    @Test
    @DisplayName("실제 문서에 걸면 통과와 차단이 <둘 다> 나온다 — 한쪽만 나오면 규칙이 아니라 상수다")
    void splitsRealDocuments() throws Exception {
        if (!Files.isDirectory(DOC_DIR)) {
            return; // 문서가 없는 환경 — 건너뛴다
        }
        List<Path> files;
        try (var stream = Files.list(DOC_DIR)) {
            files = stream.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        if (files.size() < 2) {
            return; // 가를 것이 없으면 볼 것도 없다
        }

        ObjectMapper mapper = new ObjectMapper();
        int passed = 0;
        int blocked = 0;
        for (Path file : files) {
            GeneratedDocumentFile parsed = mapper.readValue(file.toFile(), GeneratedDocumentFile.class);
            String contentMd = parsed.document() == null ? null : parsed.document().contentMd();
            boolean ok = TypeMaterialRule.missingMaterialOf(contentMd, ProblemType.MATCHING) == null;
            System.out.printf("%-14s rows=%-3d %s%n",
                    file.getFileName(), TypeMaterialRule.comparableRowsOf(contentMd), ok ? "통과" : "차단");
            if (ok) {
                passed++;
            } else {
                blocked++;
            }
        }

        assertThat(passed).as("표가 있는 문서는 통과해야 한다 — 전부 막히면 규칙이 너무 빡빡하다").isPositive();
        assertThat(blocked).as("표가 없는 옛 문서는 막혀야 한다 — 전부 통과하면 규칙이 헛돈다").isPositive();
    }
}
