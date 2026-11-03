package project.study.study_project.llm.service;

import project.study.study_project.domainsetting.support.DomainCatalog;
import project.study.study_project.global.common.DomainCode;

import java.nio.file.Path;

/**
 * 배치 결과 파일을 들여올 때 문제 파일과 문서 파일이 함께 지키는 검사(REVIEW 2026-11-01 순위 6).
 * {@link DraftImportService}와 {@link DocumentImportService}가 같이 부른다.
 */
final class DraftFileChecks {

    private DraftFileChecks() {
    }

    /**
     * 파일의 분야가 지금도 등록돼 있는지 본다. 배치는 현황 파일로 분야 목록을 보므로, 배치를 돌린 뒤
     * 관리자가 그 분야를 지우면 파일이 "한때는 맞았던" 분야로 도착한다. 여기서 안 막으면 외래키(V20)가
     * 뒤늦게 막아 원인이 안 보이는 예외로 죽는다. IllegalArgumentException이라 들여오기 이력을 남기지
     * 않는다. 분야를 다시 등록하면 다음 기동에 그 파일을 다시 시도한다({@code DraftImportRunner}).
     */
    static void requireRegisteredDomain(DomainCatalog catalog, DomainCode domain, Path file) {
        if (!catalog.exists(domain)) {
            throw new IllegalArgumentException("등록되지 않은 분야입니다: " + domain + " (" + file.getFileName() + ")");
        }
    }

    /**
     * 생성 당시 모델 이름. 지금 설정값을 쓰면 모델을 바꾼 뒤 들여온 옛 파일이 새 모델 이름으로 둔갑해
     * 승인율 비교가 흐려진다. 이름이 없는 파일은 "unknown"으로 적는다.
     */
    static String modelOf(String model) {
        return model == null || model.isBlank() ? "unknown" : model;
    }
}
