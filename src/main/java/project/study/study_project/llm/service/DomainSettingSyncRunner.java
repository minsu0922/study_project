package project.study.study_project.llm.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 기동 시 {@code domain_setting} 테이블을 {@code Domain} enum에 맞춰 둔다.
 *
 * <p>실제 동기화 규칙은 {@link DomainSettingService#syncWithEnum()}에 있다 — 이 클래스는
 * "언제 부를 것인가"만 책임진다({@code TopicQueueSyncRunner}와 같은 역할 분리).
 *
 * <p><b>부팅을 막지 않는 다른 러너들과 달리, 여기서는 예외를 삼키지 않는다.</b>
 * {@code TopicQueueSyncRunner}·{@code DraftImportRunner}는 파일 형식이 깨져도 조용히
 * 건너뛴다 — 대기열·초안 흡수는 부가 기능이라 그것 때문에 퀴즈 풀이까지 죽으면 안 되기
 * 때문이다. 하지만 분야 설정은 다르다. 이 테이블이 비거나 어긋난 채로 넘어가면, 관리 화면과
 * 배치가 서로 다른 분야 목록을 보게 되는데 그 증상은 "왜 저 분야만 문제가 안 늘지"처럼
 * 한참 뒤에야 드러난다(서비스 클래스 Javadoc 참고). 동기화가 실패했다면 그 순간 기동을
 * 멈춰 원인을 바로 보는 편이, 잘못된 상태로 조용히 뜨는 것보다 낫다.
 */
@Slf4j
@Component
/*
 * @Order(4) — 주제 대기열 동기화(TopicQueueSyncRunner, @Order(5))보다 한 칸 앞이다. 그 자체로는
 * 순서상 의미가 없다(둘은 서로 다른 테이블을 다뤄 겹치지 않는다). 진짜 이유는
 * DomainSettingExporter(@Order(60), 기동 때 한 번 파일을 내보낸다)에 있다 — 내보내기가 이 동기화보다 먼저 돌면
 * 행을 만들기도 전에 <b>빈 테이블</b>을 파일로 써 버리고, 그 파일이 그대로 커밋되면 클라우드
 * 배치는 다음 실행까지 분야 설정이 하나도 없는 채로 하루를 돈다. 앞자리 숫자(4)를 골라 둔
 * 것은 "테이블을 채우는 동기화 러너들은 앞쪽에 모아 둔다"는 읽기 편의를 위해서다
 * (TopicQueueSyncRunner의 같은 주석 참고).
 */
@Order(4)
@RequiredArgsConstructor
public class DomainSettingSyncRunner implements ApplicationRunner {

    private final DomainSettingService domainSettingService;

    @Override
    public void run(ApplicationArguments args) {
        domainSettingService.syncWithEnum();
        log.info("분야 설정 동기화 완료");
    }
}
