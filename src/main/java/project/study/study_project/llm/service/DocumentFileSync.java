package project.study.study_project.llm.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.dto.GeneratedDocumentFile;
import project.study.study_project.llm.support.JsonMappers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 검수함에서 고친 문서 본문을 배치 파일({@code generated/documents/*.json})에도 옮긴다.
 *
 * <p>문제 배치는 DB를 못 보고 이 파일을 근거로 읽는다. 초안만 고치면 그 주기의 문제가
 * 고치기 전의 틀린 문장으로 나온다. 파일은 앱이 쓰고 사람이 커밋한다(스냅샷 파일과 같다).
 */
@Slf4j
@Component
public class DocumentFileSync {

    private final Path dir;

    public DocumentFileSync(@Value("${llm.import.dir:generated}") String importDir) {
        this.dir = Path.of(importDir).resolve(DocumentImportService.DOCUMENT_SUBDIR);
    }

    /**
     * slug와 고치기 전 본문이 같은 편을 찾아 본문을 바꾼다. 실패해도 예외를 던지지 않는다 —
     * 검수함의 수정은 이미 맞게 끝났고, 파일은 손으로도 고칠 수 있다.
     *
     * @return 고친 파일 이름. 못 찾았거나 못 썼으면 비어 있다
     */
    public Optional<String> replaceContent(String slug, String before, String after) {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> candidates = files
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().startsWith("_"))
                    .sorted()
                    .toList();
            for (Path file : candidates) {
                if (rewriteIfMatches(file, slug, before, after)) {
                    log.info("배치 파일 본문도 고침: {} — 커밋해야 다음 배치에 반영됩니다", file);
                    return Optional.of(file.getFileName().toString());
                }
            }
        } catch (IOException e) {
            log.warn("배치 파일 목록을 읽지 못했습니다({}): {}", dir, e.getMessage());
            return Optional.empty();
        }
        log.warn("고친 문서({})의 배치 파일을 찾지 못했습니다 — 파일은 그대로입니다", slug);
        return Optional.empty();
    }

    private boolean rewriteIfMatches(Path file, String slug, String before, String after) {
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            GeneratedDocumentFile parsed = JsonMappers.LENIENT.readValue(raw, GeneratedDocumentFile.class);
            boolean beginner = matches(parsed.document(), slug, before);
            boolean advanced = matches(parsed.advancedDocument(), slug, before);
            if (!beginner && !advanced) {
                return false;
            }
            write(file, rewritten(raw, parsed, beginner, before, after));
            return true;
        } catch (IOException e) {
            log.warn("배치 파일을 고치지 못했습니다({}): {}", file.getFileName(), e.getMessage());
            return false;
        }
    }

    private static boolean matches(GeneratedDocumentItem item, String slug, String content) {
        return item != null && item.slug() != null && item.slug().strip().equals(slug)
                && content.equals(item.contentMd());
    }

    /**
     * 본문 문자열 하나만 글자 그대로 바꾼다. 파일을 통째로 다시 쓰면 손으로 쓴 파일은 줄 배치까지
     * 바뀌어 커밋 차이가 파일 전체가 된다. 글자 그대로 못 찾을 때만 통째로 다시 쓴다.
     */
    static String rewritten(String raw, GeneratedDocumentFile parsed, boolean beginner, String before, String after)
            throws JsonProcessingException {
        String from = JsonMappers.STRICT.writeValueAsString(before);
        String to = JsonMappers.STRICT.writeValueAsString(after);
        int at = raw.indexOf(from);
        if (at >= 0 && raw.indexOf(from, at + 1) < 0) {
            String replaced = raw.substring(0, at) + to + raw.substring(at + from.length());
            GeneratedDocumentFile check = JsonMappers.LENIENT.readValue(replaced, GeneratedDocumentFile.class);
            GeneratedDocumentItem item = beginner ? check.document() : check.advancedDocument();
            if (item != null && after.equals(item.contentMd())) {
                return replaced;
            }
        }
        GeneratedDocumentItem old = beginner ? parsed.document() : parsed.advancedDocument();
        GeneratedDocumentItem fixed = new GeneratedDocumentItem(old.title(), old.slug(), after, old.tags());
        GeneratedDocumentFile file = new GeneratedDocumentFile(parsed.note(), parsed.date(), parsed.generatedAt(),
                parsed.domain(), parsed.model(), beginner ? fixed : parsed.document(),
                beginner ? parsed.advancedDocument() : fixed, parsed.factFindings());
        return JsonMappers.STRICT.writerWithDefaultPrettyPrinter().writeValueAsString(file);
    }

    /** 임시 파일에 쓰고 옮긴다. 쓰다 멈춰도 배치 파일이 반쯤 쓰인 채로 남지 않는다. */
    private static void write(Path file, String json) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, json, StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
