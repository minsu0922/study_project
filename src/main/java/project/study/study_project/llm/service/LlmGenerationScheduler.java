package project.study.study_project.llm.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import project.study.study_project.llm.dto.LlmGenerateRequest;

/**
 * 일일 자동 생성 배치 — 문서 13, ADR-0006.
 *
 * <p>오늘의 퀴즈(ADR-0005)는 "PC가 꺼져 있으면 그날 기능이 죽는다"는 이유로 배치를 버리고
 * 지연 생성을 택했다. 여기서는 반대로 배치를 <b>채택</b>한다 — 근거는 실패 비용의 차이:
 * 문제 생성은 사용자 대면 기능이 아니라서 하루 안 돌아도 아무 일도 일어나지 않는다
 * (창고가 하루 안 채워질 뿐). 실패해도 무해한 작업이야말로 배치를 배우기에 안전한 자리다.
 *
 * <p>{@code @ConditionalOnProperty}로 <b>기본 꺼짐</b>: 켜는 것을 명시적 선택으로 만든다.
 * API 비용이 발생하는 작업이 설정 실수로 몰래 돌면 안 되기 때문. 꺼져 있으면 이 클래스가
 * 빈으로 등록되지 않아 스케줄링 인프라 자체가 뜨지 않는다(@EnableScheduling도 여기 붙인 이유).
 */
@Slf4j
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "llm.generation.batch-enabled", havingValue = "true")
@RequiredArgsConstructor
public class LlmGenerationScheduler {

    private final LlmProblemService llmProblemService;

    @Value("${llm.generation.batch-count:5}")
    private int batchCount;

    /**
     * 매일 06시(설정 가능) — domain/difficulty를 null로 넘겨 "가장 부족한 칸 자동 선택"을 태운다.
     * 유형은 객관식 고정: 보기·해설이 함께 생성돼 검수 가치가 가장 높은 유형이다
     * (OX·단답형이 필요하면 관리자 수동 버튼으로).
     *
     * <p>예외를 삼키고 로그만 남기는 이유: 스케줄러 스레드에서 예외가 새 나가면 다음 실행까지
     * 영향을 줄 수 있고, 어차피 이 작업은 실패해도 무해하다(다음 날 다시 시도).
     */
    @Scheduled(cron = "${llm.generation.batch-cron:0 0 6 * * *}")
    public void generateDaily() {
        try {
            int saved = llmProblemService.generate(
                    new LlmGenerateRequest(null, null, null, batchCount)).size();
            log.info("LLM 일일 생성 배치 완료: {}건 초안 저장(검수 대기)", saved);
        } catch (Exception e) {
            log.error("LLM 일일 생성 배치 실패(다음 실행에 재시도): {}", e.getMessage(), e);
        }
    }
}
