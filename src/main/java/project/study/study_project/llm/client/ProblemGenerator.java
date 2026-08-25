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
     * @param rejectionNotes 과거 검수에서 거절된 사례(지문+사유). 비어 있으면 프롬프트에서 생략된다.
     *                       사람의 검수 결과를 다음 생성에 되먹이는 통로다(docs/14)
     * @return 생성된 문제 목록(모델이 count보다 적게/많이 줄 수도 있어 호출부가 방어)
     */
    default List<GeneratedProblemItem> generate(Domain domain, Difficulty difficulty, ProblemType type,
                                                int count, List<String> avoidQuestions,
                                                List<RejectionNote> rejectionNotes) {
        return generate(domain, difficulty, type, count, avoidQuestions, rejectionNotes, null);
    }

    /**
     * 근거 문서를 주고 문제를 생성한다 — 2단계(docs/15).
     *
     * <p><b>기존 메서드를 지우지 않고 기본 구현으로 남긴 이유</b>: 근거 문서가 있는 생성은
     * 배치(4일 주기)만의 사정이고, 관리자 화면의 즉시 생성은 앞으로도 근거 없이 만든다.
     * 호출부 절반이 항상 {@code null}을 넘기게 만드느니 인자 없는 형태를 남겨 두는 편이
     * 읽기 좋다 — {@code generate(..., null)}이 코드에 흩어지면 그 null이 무엇을 뜻하는지가
     * 호출부마다 사라진다.
     *
     * @param sourceDocument 근거 문서. {@code null}이면 근거 없이(모델의 지식으로) 생성한다
     */
    default List<GeneratedProblemItem> generate(Domain domain, Difficulty difficulty, ProblemType type,
                                                int count, List<String> avoidQuestions,
                                                List<RejectionNote> rejectionNotes,
                                                SourceDocument sourceDocument) {
        return generate(domain, difficulty, type, count, avoidQuestions, rejectionNotes,
                sourceDocument, null);
    }

    /**
     * <b>묻는 형태를 지목해</b> 문제를 생성한다 — 2026-08-25 신설.
     *
     * <p><b>왜 필요해졌나.</b> 같은 날 중급에 다섯 형태를 열었는데({@link QuestionKind}) 실물이
     * 세 번 연속 {@link QuestionKind#SITUATION}으로만 나왔다. 프롬프트에 형태를 나열해 두는 것만으로는
     * 강제력이 없었던 것이다 — 이 저장소가 여러 번 확인한 그대로다: <b>숫자나 값을 박지 않은
     * 지시는 지켜지지 않는다</b>(해설 400~700자, 문서 절 개수, 고급 재료 개수가 전부 그랬다).
     *
     * <p>모델이 상황형으로 돌아가는 것은 게을러서가 아니라 <b>재료가 가장 풍부해서</b>다.
     * 문서의 {@code ## 실무에서는 이렇게 쓴다} 절이 곧 상황 재료라, 아무 말 없으면 늘 그쪽이 이긴다.
     * 그러니 다른 형태를 얻으려면 사람이 지목하는 수밖에 없다.
     *
     * <p><b>배치가 아니라 관리 화면을 위한 인자다.</b> 4일 주기 배치는 한 번에 다섯을 뽑으므로
     * 프롬프트의 "SITUATION 최소 2개" 규칙 안에서 모델이 알아서 섞으면 된다. 문제는 검수 뒤
     * 한두 건을 <b>메워 넣을 때</b>다 — 그때는 개수가 적어 모델에게 섞을 여지가 없다.
     *
     * @param requestedKind 지목할 형태. {@code null}이면 모델이 고른다(배치의 기본 동작)
     */
    List<GeneratedProblemItem> generate(Domain domain, Difficulty difficulty, ProblemType type,
                                        int count, List<String> avoidQuestions,
                                        List<RejectionNote> rejectionNotes,
                                        SourceDocument sourceDocument,
                                        QuestionKind requestedKind);
}
