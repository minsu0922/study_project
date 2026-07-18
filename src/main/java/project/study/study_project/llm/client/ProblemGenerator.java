package project.study.study_project.llm.client;

import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;

import java.util.List;

/**
 * 문제 생성기 추상화 — 실제 구현은 {@link ClaudeProblemGenerator}(Claude API).
 *
 * <p>인터페이스로 분리한 이유는 <b>테스트</b>다: 서비스 로직(부족 칸 선택·초안 저장·승인 변환)을
 * 검증할 때마다 실제 API를 호출하면 돈이 들고 느리고 결과가 매번 달라 단정(assert)이 불가능하다.
 * 테스트에서는 정해진 문제를 돌려주는 가짜(fake) 구현을 주입한다.
 * (외부 세계와의 경계에 인터페이스를 두는 전형적인 포트-어댑터 패턴)
 */
public interface ProblemGenerator {

    /**
     * 지정 규격의 문제를 생성한다.
     *
     * @param domain         분야 (필수)
     * @param difficulty     난이도 (필수)
     * @param type           유형 (필수 — MVP 자동채점 3종만)
     * @param count          생성 개수
     * @param avoidQuestions 중복 회피 목록 — 기존 문제·대기 초안의 질문 텍스트
     * @return 생성된 문제 목록(모델이 count보다 적게/많이 줄 수도 있어 호출부가 방어)
     */
    List<GeneratedProblemItem> generate(Domain domain, Difficulty difficulty, ProblemType type,
                                        int count, List<String> avoidQuestions);
}
