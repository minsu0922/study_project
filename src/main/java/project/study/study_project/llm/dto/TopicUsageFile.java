package project.study.study_project.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 주제 사용 기록 파일({@code _topic-usage.json}, {@code _code-topic-usage.json})의 형태
 * (REVIEW 2026-11-01 순위 5, docs/28 8장).
 *
 * <p><b>배치만 쓴다.</b> 대기열 파일({@code _topics.json})은 앱만 쓴다. 한 파일을 둘이 쓰면 앱이 순서를
 * 바꾼 사이 배치가 사용일을 찍어 "배치로 보내기"가 충돌했다. 배치는 두 파일을 합쳐 다음 주제를 고르고,
 * 앱은 기동할 때 이 파일을 DB에 읽어 들인다.
 *
 * @param usage 주제마다 한 줄. 쓸 때마다 새 줄을 더하지 않고 그 주제의 줄을 고친다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TopicUsageFile(String note, List<Usage> usage) {

    /**
     * @param id         대기열 줄의 DB 번호. 손으로 적어 아직 번호가 없는 줄이면 null
     * @param domain     분야 코드. 번호가 없을 때 주제 글자와 함께 짝을 찾는다
     * @param topic      주제 글자. 사람이 읽을 수 있게 함께 적는다
     * @param lastUsedAt 마지막으로 쓴 날(yyyy-MM-dd, 한국 날짜)
     * @param usedCount  지금까지 쓴 횟수
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Usage(Long id, String domain, String topic, String lastUsedAt, Integer usedCount) {

        /** 이 기록이 대기열의 그 줄 것인가. 번호가 둘 다 있으면 번호로, 아니면 분야와 주제 글자로 본다 */
        public boolean matches(Long entryId, String entryDomain, String entryTopic) {
            if (id != null && entryId != null) {
                return id.equals(entryId);
            }
            return domain != null && topic != null && entryDomain != null && entryTopic != null
                    && domain.trim().equalsIgnoreCase(entryDomain.trim()) && topic.trim().equals(entryTopic.trim());
        }
    }
}
