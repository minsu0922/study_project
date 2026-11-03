package project.study.study_project.llm.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 초안에 JSON으로 저장한 검수 지적 목록을 처리하는 공통 단계(REVIEW 2026-11-01 순위 6).
 * 문제 초안의 AI 지적과 문서 초안의 사실 지적이 같이 쓴다. 고치는 방식은 각 서비스에 남긴다.
 */
public final class FindingList {

    private FindingList() {
    }

    /** 처리 기록을 붙일 수 있는 지적. 처리하면 기록을 단 새 지적을 돌려준다 */
    public interface Resolvable<T> {
        T resolvedWith(String resolution);
    }

    /**
     * @param label 화면에 보일 지적 이름(예: "AI 검수 지적")
     * @return 저장된 목록. 검수를 안 돌린 초안이면 {@code null}
     */
    public static <T> List<T> read(ObjectMapper objectMapper, String json, TypeReference<List<T>> type, String label) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.COMMON_001, label + "을 읽지 못했습니다.");
        }
    }

    /**
     * 그 번호의 지적이 있고 아직 처리하지 않았는지 본다. 아니면 화면에 보일 이유로 던진다.
     *
     * @param resolutionOf 처리 기록을 읽는 법. 문제 지적은 옛 기록 형식까지 읽는다
     */
    public static <T> T requireOpen(List<T> findings, int index, Function<T, String> resolutionOf) {
        if (findings == null || index < 0 || index >= findings.size()) {
            throw new BusinessException(ErrorCode.COMMON_001, "그런 지적이 없습니다.");
        }
        T finding = findings.get(index);
        if (resolutionOf.apply(finding) != null) {
            throw new BusinessException(ErrorCode.COMMON_001, "이미 처리한 지적입니다.");
        }
        return finding;
    }

    /** 그 번호의 지적만 처리 기록을 단 새 목록. 원래 목록은 고치지 않는다 */
    public static <T extends Resolvable<T>> List<T> resolve(List<T> findings, int index, String resolution) {
        List<T> updated = new ArrayList<>(findings);
        updated.set(index, findings.get(index).resolvedWith(resolution));
        return updated;
    }

    /** 목록을 저장할 JSON으로. record 직렬화라 실패하면 버그다 */
    public static String write(ObjectMapper objectMapper, List<?> findings) {
        try {
            return objectMapper.writeValueAsString(findings);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("검수 지적 직렬화 실패", e);
        }
    }
}
