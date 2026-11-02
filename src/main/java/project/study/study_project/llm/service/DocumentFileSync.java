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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 고친 문서를 원본 배치 파일({@code generated/documents/*.json})에도 옮긴다.
 *
 * <p>문제 배치는 DB를 못 보고 이 파일을 근거로 읽는다. 초안이나 정식 문서만 고치면 다음 문제가
 * 고치기 전의 틀린 문장으로 나온다. 그래서 파일이 늘 최신본을 따르게 한다 — 승인 전에는 초안,
 * 승인 뒤에는 정식 문서. 모델이 처음 쓴 원본은 git 이력에 남는다. 파일은 앱이 쓰고, 관리 콘솔의 "배치로 보내기"로 커밋·push한다(docs/29).
 */
@Slf4j
@Component
public class DocumentFileSync {

    private final Path dir;

    public DocumentFileSync(@Value("${llm.import.dir:generated}") String importDir) {
        this.dir = Path.of(importDir).resolve(DocumentImportService.DOCUMENT_SUBDIR);
    }

    /**
     * 정식 문서 한 편의 지금 모습. 배치 파일에서는 {@code fileSlug}로 그 편을 찾는다.
     *
     * @param fileSlug 배치 파일에 적힌 slug. slug를 바꾼 직후에는 고치기 전 slug다
     */
    public record Latest(String fileSlug, String slug, String title, String contentMd) {
    }

    /**
     * 검수함에서 초안 본문을 고쳤을 때. slug와 고치기 전 본문이 같은 편만 바꾼다 —
     * 초안은 아직 파일과 같아야 하므로 본문까지 맞춰 보는 쪽이 안전하다.
     *
     * @return 고친 파일 이름. 못 찾았거나 못 썼으면 비어 있다
     */
    public Optional<String> replaceContent(String slug, String before, String after) {
        for (Path file : files()) {
            Optional<String> done = rewrite(file, item -> slug.equals(slugOf(item)) && before.equals(item.contentMd())
                    ? new GeneratedDocumentItem(item.title(), item.slug(), after, item.tags()) : null);
            if (done.isPresent()) {
                log.info("배치 파일 본문도 고침: {} — 커밋해야 다음 배치에 반영됩니다", done.get());
                return done;
            }
        }
        log.warn("고친 문서({})의 배치 파일을 찾지 못했습니다 — 파일은 그대로입니다", slug);
        return Optional.empty();
    }

    /**
     * 정식 문서들을 배치 파일에 맞춘다. 제목·slug·본문 중 다른 것이 있는 편만 고친다.
     * 배치 파일이 없는 문서(손으로 등록한 문서)는 건너뛴다.
     *
     * @return 고친 파일 이름들
     */
    public List<String> syncLatest(List<Latest> latest) {
        Map<String, Latest> bySlug = new HashMap<>();
        for (Latest l : latest) {
            bySlug.put(l.fileSlug(), l);
        }
        List<String> changed = new ArrayList<>();
        for (Path file : files()) {
            rewrite(file, item -> {
                Latest l = bySlug.get(slugOf(item));
                if (l == null) {
                    return null;
                }
                GeneratedDocumentItem fixed = new GeneratedDocumentItem(l.title(), l.slug(), l.contentMd(), item.tags());
                return fixed.equals(item) ? null : fixed;
            }).ifPresent(changed::add);
        }
        if (!changed.isEmpty()) {
            log.info("정식 문서를 배치 파일에 맞췄습니다: {} — 커밋해야 다음 배치에 반영됩니다", changed);
        }
        return changed;
    }

    /** 편 하나를 받아 바꿀 모습을 돌려준다. 그대로 둘 편이면 {@code null}. */
    private interface ItemChange {
        GeneratedDocumentItem apply(GeneratedDocumentItem item);
    }

    private List<Path> files() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().startsWith("_"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("배치 파일 목록을 읽지 못했습니다({}): {}", dir, e.getMessage());
            return List.of();
        }
    }

    /** 파일의 두 편에 바꿀 것을 적용해 쓴다. 바뀐 편이 없으면 파일을 건드리지 않는다. */
    private Optional<String> rewrite(Path file, ItemChange change) {
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            GeneratedDocumentFile parsed = JsonMappers.LENIENT.readValue(raw, GeneratedDocumentFile.class);
            GeneratedDocumentItem beginner = changeOf(parsed.document(), change);
            GeneratedDocumentItem advanced = changeOf(parsed.advancedDocument(), change);
            if (beginner == null && advanced == null) {
                return Optional.empty();
            }
            GeneratedDocumentFile target = new GeneratedDocumentFile(parsed.note(), parsed.date(), parsed.generatedAt(),
                    parsed.domain(), parsed.model(),
                    beginner != null ? beginner : parsed.document(),
                    advanced != null ? advanced : parsed.advancedDocument(), parsed.factFindings());
            write(file, rewritten(raw, parsed, target));
            return Optional.of(file.getFileName().toString());
        } catch (IOException e) {
            log.warn("배치 파일을 고치지 못했습니다({}): {}", file.getFileName(), e.getMessage());
            return Optional.empty();
        }
    }

    private static GeneratedDocumentItem changeOf(GeneratedDocumentItem item, ItemChange change) {
        return item == null ? null : change.apply(item);
    }

    private static String slugOf(GeneratedDocumentItem item) {
        return item.slug() == null ? null : item.slug().strip();
    }

    /**
     * 본문 문자열만 글자 그대로 바꾼다. 파일을 통째로 다시 쓰면 손으로 쓴 파일은 줄 배치까지
     * 바뀌어 커밋 차이가 파일 전체가 된다. 제목·slug가 바뀌었거나 글자 그대로 못 찾을 때만 통째로 다시 쓴다.
     */
    static String rewritten(String raw, GeneratedDocumentFile parsed, GeneratedDocumentFile target)
            throws JsonProcessingException {
        String text = replaceBody(raw, parsed.document(), target.document());
        text = text == null ? null : replaceBody(text, parsed.advancedDocument(), target.advancedDocument());
        if (text != null) {
            GeneratedDocumentFile check = JsonMappers.LENIENT.readValue(text, GeneratedDocumentFile.class);
            if (Objects.equals(check.document(), target.document())
                    && Objects.equals(check.advancedDocument(), target.advancedDocument())) {
                return text;
            }
        }
        return JsonMappers.STRICT.writerWithDefaultPrettyPrinter().writeValueAsString(target);
    }

    /** 본문만 다르면 그 글자를 바꾼 문자열, 그대로면 원래 문자열, 본문 말고도 다르거나 못 찾으면 {@code null}. */
    private static String replaceBody(String raw, GeneratedDocumentItem before, GeneratedDocumentItem after)
            throws JsonProcessingException {
        if (Objects.equals(before, after)) {
            return raw;
        }
        if (before == null || after == null || !Objects.equals(before.title(), after.title())
                || !Objects.equals(before.slug(), after.slug()) || !Objects.equals(before.tags(), after.tags())) {
            return null;
        }
        String from = JsonMappers.STRICT.writeValueAsString(before.contentMd());
        String to = JsonMappers.STRICT.writeValueAsString(after.contentMd());
        int at = raw.indexOf(from);
        if (at < 0 || raw.indexOf(from, at + 1) >= 0) {
            return null;
        }
        return raw.substring(0, at) + to + raw.substring(at + from.length());
    }

    /** 임시 파일에 쓰고 옮긴다. 쓰다 멈춰도 배치 파일이 반쯤 쓰인 채로 남지 않는다. */
    private static void write(Path file, String json) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, json, StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
