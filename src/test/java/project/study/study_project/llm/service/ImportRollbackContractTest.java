package project.study.study_project.llm.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 파일 흡수 메서드는 IOException에도 롤백해야 한다.
 * 기본값은 검사 예외를 커밋하므로, 반쯤 저장된 초안이 이력 없이 남아 다음 부팅에 또 들어온다.
 */
class ImportRollbackContractTest {

    static Stream<Arguments> importMethods() {
        return Stream.of(
                Arguments.of(DraftImportService.class, "importFile"),
                Arguments.of(DocumentImportService.class, "importFile"),
                Arguments.of(DocumentImportService.class, "backfillFactFindings"));
    }

    @ParameterizedTest(name = "{0}.{1}")
    @MethodSource("importMethods")
    @DisplayName("파일 흡수 트랜잭션은 IOException에도 롤백한다")
    void rollsBackOnIOException(Class<?> type, String method) throws Exception {
        Transactional tx = type.getMethod(method, Path.class).getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.rollbackFor()).contains(IOException.class);
    }
}
