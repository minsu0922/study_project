package project.study.study_project.llm.client;

import java.util.List;

/**
 * 제목 생성기 추상화 — 실제 구현은 {@link ClaudeTitleGenerator}(Claude API).
 *
 * <p>{@link ProblemGenerator}와 같은 이유로 인터페이스를 둔다: 백필 서비스가 하는 일
 * (짝짓기·자르기·빈 값 거르기)을 검증할 때마다 실제 API를 부르면 돈이 들고 결과가 매번 달라
 * 단정할 수가 없다. 테스트는 정해진 제목을 돌려주는 가짜를 주입한다.
 */
public interface TitleGenerator {

    /**
     * 문제들에 붙일 목록 제목을 만든다.
     *
     * @param problems 제목이 없는 문제들(id + 지문). 지문만 주고 보기·해설은 주지 않는다 —
     *                 이름을 짓는 데 필요한 것은 지문이고, 나머지는 토큰만 늘린다
     * @return 문제별 제목. <b>요청보다 적거나 순서가 다를 수 있다</b> — 짝짓기는 호출부가
     *         {@code problemId}로 한다(그 이유는 {@link GeneratedTitle} 주석)
     */
    List<GeneratedTitle> generateTitles(List<UntitledProblem> problems);

    /**
     * 제목을 붙일 대상 하나 — id와 지문뿐이다.
     *
     * <p>엔티티({@code Problem})를 그대로 넘기지 않은 이유: 클라이언트 계층이 도메인 엔티티에
     * 묶이면 테스트에서 가짜를 만들 때 JPA 엔티티를 조립해야 하고, 무엇보다 <b>보기·해설까지
     * 딸려 오는</b> 것을 막을 방법이 없다. 프롬프트에 실릴 것만 담은 값 객체로 좁힌다.
     */
    record UntitledProblem(long id, String question) {
    }
}
