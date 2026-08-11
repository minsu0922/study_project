package project.study.study_project.llm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 흡수 완료한 생성 결과 파일의 이력 — {@code imported_draft_file} 테이블(V7)과 대응.
 *
 * <p>GitHub Actions가 저장소에 떨궈 둔 {@code generated/YYYY-MM-DD.json}을 로컬 앱이
 * 검수 대기 초안으로 가져온 뒤, "이 파일은 처리했다"고 표시하는 도장 역할을 한다(docs/14).
 * 이게 없으면 앱을 켤 때마다 같은 파일을 다시 읽어 초안이 무한정 복제된다.
 *
 * <p><b>@GeneratedValue가 없는 이유</b>: 파일명 자체가 신원이라 자연키를 PK로 쓴다(V7 주석).
 * 대신 JPA에서 식별자를 직접 넣는 엔티티는 {@code save()} 시 "이미 있는 행인가"를 확인하려고
 * SELECT를 한 번 더 한다. 하루 한 건 수준이라 성능 문제가 없고, 오히려 그 SELECT가
 * 중복 흡수를 한 번 더 걸러 주는 셈이라 그대로 둔다.
 *
 * <p>{@code @CreatedDate} 감사 필드 대신 {@code importedAt}을 직접 넣는 이유: 이 값은 "행이 만들어진 시각"이
 * 아니라 "흡수를 끝낸 시각"이라는 업무적 의미가 있어, 감사 필드로 자동 채우기보다
 * 서비스가 명시적으로 기록하는 편이 의도가 드러난다.
 */
@Entity
@Table(name = "imported_draft_file")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImportedDraftFile {

    /** generated/ 아래의 파일명. 예: {@code 2026-08-12.json} */
    @Id
    @Column(length = 100)
    private String filename;

    @Column(name = "imported_at", nullable = false)
    private LocalDateTime importedAt;

    /** 실제로 저장된 초안 수 — 규약 위반으로 걸러진 문제가 있으면 파일의 문제 수보다 적다. */
    @Column(name = "draft_count", nullable = false)
    private int draftCount;

    private ImportedDraftFile(String filename, int draftCount) {
        this.filename = filename;
        this.draftCount = draftCount;
        this.importedAt = LocalDateTime.now();
    }

    /** 흡수 완료 도장. 결과가 0건이어도 기록한다 — 못 건진 파일을 매 부팅마다 다시 읽지 않기 위해. */
    public static ImportedDraftFile of(String filename, int draftCount) {
        return new ImportedDraftFile(filename, draftCount);
    }
}
