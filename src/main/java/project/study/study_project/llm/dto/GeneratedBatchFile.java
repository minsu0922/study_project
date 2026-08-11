package project.study.study_project.llm.dto;

import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.GeneratedProblemItem;

import java.util.List;

/**
 * 하루치 생성 결과 파일({@code generated/YYYY-MM-DD.json})의 형태 — docs/14.
 *
 * <p>이 파일이 <b>클라우드와 내 PC를 잇는 유일한 통로</b>다. GitHub Actions가 쓰고
 * ({@code DraftGeneratorCli}), 로컬 앱이 기동할 때 읽는다({@code DraftImportService}).
 * 택배함에 넣어 두는 상자 한 개라고 보면 된다.
 *
 * <p><b>problems 타입으로 {@link GeneratedProblemItem}을 그대로 재사용한 이유</b>:
 * 파일 전용 DTO를 따로 만들면 "모델이 준 것 → 파일 → 초안" 사이에 변환 코드가 두 벌 생기고,
 * 언젠가 한쪽만 필드가 추가돼 조용히 값이 유실된다. 같은 타입을 쓰면 흡수 시점에
 * 기존 검증 경로({@code LlmProblemService.toDraft})에 <b>그대로 태울 수 있다</b> —
 * 즉 클라우드에서 왔든 관리자 버튼에서 왔든 완전히 같은 문을 통과한다(단일 경로 원칙).
 *
 * <p><b>이 파일을 신뢰하지 않는다</b>는 점이 중요하다. 저장소에 커밋된 JSON은 사람이 손으로
 * 고칠 수도 있으므로, 흡수 단계에서 규약 검증(객관식 정답 정확히 1개 등)을 반드시 다시 한다.
 * 파일은 "검수 대기함에 넣어 달라는 요청서"일 뿐 확정된 데이터가 아니다.
 *
 * @param note        파일을 직접 열어 본 사람을 위한 안내 문구(기계는 읽지 않음)
 * @param date        생성 기준 날짜(한국 날짜) — 파일명과 같다. 순환 순번의 근거값
 * @param generatedAt 실제 생성 시각(ISO-8601, UTC) — 예약이 얼마나 밀렸는지 추적용
 * @param domain      생성 대상 분야
 * @param difficulty  생성 대상 난이도
 * @param type        문제 유형(현재 배치는 객관식 고정)
 * @param model       실제 호출에 쓴 모델 ID — 초안의 model 컬럼에 그대로 기록해 품질 비교에 쓴다
 * @param problems    모델이 반환한 문제 목록(검증 전 원본)
 */
public record GeneratedBatchFile(
        String note,
        String date,
        String generatedAt,
        Domain domain,
        Difficulty difficulty,
        ProblemType type,
        String model,
        List<GeneratedProblemItem> problems
) {
}
