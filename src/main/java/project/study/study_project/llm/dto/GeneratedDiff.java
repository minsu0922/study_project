package project.study.study_project.llm.dto;

import java.util.List;

/**
 * 보낼 파일 하나가 마지막 커밋과 어떻게 다른지 — 배치 현황 화면의 "내용 보기".
 *
 * @param path      저장소 루트 기준 경로
 * @param lines     바뀐 줄. 첫 글자가 {@code +}면 더한 줄, {@code -}면 뺀 줄, {@code @}면 구간 머리, 공백이면 주변 줄
 * @param truncated 너무 길어 앞부분만 담았는가
 * @param error     보여 줄 수 없으면 그 이유. 보여 줄 수 있으면 빈 문자열
 */
public record GeneratedDiff(String path, List<String> lines, boolean truncated, String error) {

    public static GeneratedDiff error(String path, String reason) {
        return new GeneratedDiff(path, List.of(), false, reason);
    }
}
