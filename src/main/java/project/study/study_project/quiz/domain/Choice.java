package project.study.study_project.quiz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 객관식 보기 — DB의 {@code choice} 테이블과 대응(문서 01-data-model).
 *
 * <p>설계 메모:
 * <ul>
 *   <li><b>{@code isCorrect}(정답 여부)는 API 응답에 절대 실리면 안 된다</b> — 풀이용 조회(GET /api/quiz)에
 *       정답이 노출되면 퀴즈가 성립하지 않는다. 그래서 DTO(QuizChoiceItem)에는 id/seq/text만 담고,
 *       이 필드는 채점(POST /api/quiz/submit) 로직에서만 읽는다.
 *   <li>{@code problem}은 {@code @ManyToOne} LAZY — 보기에서 문제로 역참조할 일이 거의 없어
 *       즉시 로딩으로 낭비할 이유가 없다.
 *   <li>MVP는 문제당 정답 보기 1개를 가정한다(문서 01). 복수 정답은 로드맵에서 다룬다.
 * </ul>
 */
@Entity
@Table(name = "choice")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Choice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "problem_id", nullable = false)
    private Problem problem;

    /** 보기 내용. 컬럼명 {@code text}는 MySQL 예약어 계열이라 DDL에서 백틱 처리돼 있다(V1 참고). */
    @Column(name = "text", nullable = false, length = 500)
    private String text;

    /**
     * 짝짓기(MATCHING)의 <b>오른쪽 항목</b> — 이 행의 {@link #text}와 짝이다. 그 밖의 유형은 {@code null}(V16).
     *
     * <p><b>한 행이 한 쌍이다.</b> 왼쪽(용어)은 {@code text}, 오른쪽(설명)은 여기. 짝을 별도
     * 문자열이나 JSON으로 두는 대신 데이터 자리에 둔 이유는 V16 주석에 적었다 — 요약하면
     * 짝이 <b>행 사이의 관계가 아니라 행 안의 값</b>이면 세거나 검증할 때 파싱할 것이 없다.
     *
     * <p><b>그래서 짝짓기의 {@code Problem.answer}는 {@code null}이다.</b> 정답이 이 행에 이미
     * 있으므로 채점 기준값을 따로 둘 이유가 없다(객관식이 {@code is_correct}에 정답을 두고
     * {@code answer}를 비우는 것과 같은 규약, docs/01). 반대로 순서 배열은 정답이 <b>행 사이의
     * 순서</b>라 어느 행에도 담기지 않아 {@code answer}에 적는다.
     *
     * <p><b>이 값은 그대로 화면에 나가도 된다 — 단, 왼쪽과 짝지어진 채로 나가면 안 된다.</b>
     * 오른쪽 열을 이 행의 {@code id}와 함께 내보내면 왼쪽 항목의 id와 맞춰 보는 것만으로
     * 답이 드러난다. 그래서 풀이용 응답은 오른쪽을 별도 목록으로 섞어 내보내고, 식별자로
     * 행 id 대신 되돌릴 수 없는 토큰을 쓴다({@code MatchToken} 주석 참고).
     */
    @Column(name = "match_text", length = 500)
    private String matchText;

    /** 정답 여부 — 채점 전용. 풀이용 API 응답에 노출 금지(클래스 주석 참고). */
    @Column(name = "is_correct", nullable = false)
    private boolean correct;

    /**
     * 이 <b>오답</b>이 왜 틀렸는지 한 줄. 정답 보기는 {@code null}이다(V15).
     *
     * <p><b>왜 해설이 아니라 보기에 붙나.</b> 해설이 오답을 "②번"처럼 번호로 가리키면
     * 학습자에게는 반드시 어긋난다 — 보기를 요청마다 다시 섞어 내보내기 때문이다
     * ({@code QuizChoiceItem.shuffledFrom}). 그렇다고 섞기를 버리면 정답 위치 편향이
     * 그대로 노출된다(승인된 17문제의 정답이 1번 6·2번 6·3번 5·<b>4번 0</b>개였다).
     * 설명을 보기 행에 붙여 두면 <b>섞은 순서 그대로 번호를 매겨도 짝이 안 어긋난다</b> —
     * 채점이 이미 보기 {@code id}로 이뤄지는 것과 같은 이치다.
     *
     * <p><b>정답 보기를 비워 두는 이유</b>: 정답의 근거는 {@code Problem.explanation}이
     * 통째로 맡는다. 양쪽에 다 적으면 같은 말이 두 벌이 되고 언젠가 한쪽만 고쳐진다.
     *
     * <p>{@code null}이 되는 또 한 경우: 이 컬럼이 생기기 <b>전에</b> 승인된 문제들.
     * 그 문제들은 오답 설명이 통짜 해설 안에 녹아 있어 옮겨 담을 값이 없다.
     * 화면은 한 보기도 값이 없으면 옛 형식으로 알아보고 통짜 해설을 그대로 그린다.
     */
    @Column(name = "rationale", length = 1000)
    private String rationale;

    /** 보기 순서(1..N). Problem 쪽 {@code @OrderBy("seq ASC")}가 이 값으로 정렬한다. */
    @Column(nullable = false)
    private int seq;

    private Choice(Problem problem, String text, String matchText, boolean correct,
                   String rationale, int seq) {
        this.problem = problem;
        this.text = text;
        this.matchText = matchText;
        this.correct = correct;
        this.rationale = rationale;
        this.seq = seq;
    }

    /**
     * 관리자 등록용 팩터리. seq는 서비스가 입력 순서대로 1..N을 부여한다
     * (관리자가 순서 번호를 직접 관리하게 하면 중복·건너뜀 실수만 늘어난다).
     */
    public static Choice of(Problem problem, String text, boolean correct, String rationale, int seq) {
        return new Choice(problem, text, null, correct, rationale, seq);
    }

    /**
     * 짝짓기 한 쌍을 만든다(V16) — 왼쪽 {@code text}와 오른쪽 {@code matchText}가 한 행이다.
     *
     * <p><b>{@code correct}를 받지 않고 {@code false}로 박는 이유.</b> 짝짓기에는 "정답 보기"라는
     * 것이 없다 — 네 쌍이 전부 정답이고, 학습자가 맞히는 것은 <b>연결</b>이다. 여기서 인자를
     * 받으면 부르는 쪽마다 {@code true}를 넣을지 {@code false}를 넣을지 고민하게 되고,
     * 그 값이 무엇을 뜻하는지는 아무도 모른다. 뜻이 없는 칸은 뜻이 없는 값으로 고정한다.
     *
     * <p><b>{@code seq}는 왼쪽 열의 표시 순서다.</b> 오른쪽은 내보낼 때 따로 섞으므로
     * 이 번호와 짝이 어긋나도 상관없다 — 오히려 어긋나야 정상이다.
     */
    public static Choice pair(Problem problem, String text, String matchText, int seq) {
        return new Choice(problem, text, matchText, false, null, seq);
    }

    /**
     * 오답 설명 없이 만드는 팩터리 — 이 필드가 생기기 전 코드와 테스트가 그대로 컴파일되게 한다.
     *
     * <p>{@code GeneratedProblemItem}이 새 필드를 뒤에 붙이며 짧은 생성자를 남긴 것과 같은
     * 처방이다. 손대야 할 곳이 많을수록 정작 봐야 할 변경이 묻힌다.
     */
    public static Choice of(Problem problem, String text, boolean correct, int seq) {
        return new Choice(problem, text, null, correct, null, seq);
    }

    /**
     * 비어 있을 때만 오답 설명을 채운다 — 이 칸이 생기기 전에 승인된 문제를 뒤늦게 메우는 데 쓴다.
     *
     * <p><b>왜 "덮어쓰지 않는다"를 엔티티가 판단하나.</b> {@code Problem.fillTitleIfAbsent}와 같은
     * 이유다. 이 판단이 서비스에 있으면 채우는 경로가 하나 더 생길 때마다 같은 {@code if}를
     * 다시 써야 하고, 한 곳에서 빠뜨리면 <b>검수자가 손으로 다듬은 설명이 모델 것으로 덮인다</b>.
     * 그 사고는 조용해서, 다듬은 사람이 다시 그 문제를 열어 보기 전까지 아무도 모른다.
     *
     * <p><b>정답 보기는 무조건 거절한다.</b> 정답의 근거는 {@code Problem.explanation}이 통째로
     * 맡기로 한 약속이고(필드 주석), 여기에 값이 들어가면 학습자가 <b>맞혔을 때</b> 화면에
     * "왜 틀렸는지"가 뜬다. 프롬프트가 정답 보기를 아예 안 보여 주는 것으로 한 번 막지만,
     * 모델이 요청하지 않은 id를 지어낼 수도 있으므로 저장 직전에 한 번 더 막는다.
     *
     * @param rationale 채울 설명. {@code null}이나 공백이면 아무것도 하지 않는다 —
     *                  모델이 빈 값을 낸 보기는 설명이 여전히 {@code null}이라
     *                  다음 실행이 다시 집어 온다. 여기서 재시도하지 않아도 손실이 없는 구조다.
     * @return 실제로 채웠으면 {@code true}. 호출부가 "몇 건 채웠는지"를 세는 데 쓴다
     */
    public boolean fillRationaleIfAbsent(String rationale) {
        if (correct || rationale == null || rationale.isBlank() || this.rationale != null) {
            return false;
        }
        this.rationale = rationale;
        return true;
    }
}
