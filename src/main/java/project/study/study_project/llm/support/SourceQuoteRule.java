package project.study.study_project.llm.support;

import project.study.study_project.global.common.Difficulty;
import project.study.study_project.llm.client.ClaudeProblemGenerator;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.SourceDocument;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 문제가 밝힌 <b>근거 인용</b>이 진짜 그 문서에서, 그것도 <b>오늘 캘 자리</b>에서 왔는지 재는 규칙.
 *
 * <p><b>왜 필요한가.</b> 프롬프트에는 "이 문서에 실제로 담긴 내용으로만 문제를 만들어라"가
 * 있었지만, 지켜졌는지 확인할 방법이 없었다. 이 저장소가 이미 배운 것이 하나 있다 —
 * <b>검사하지 않는 규칙은 없는 규칙이다</b>({@code ProblemItemRule}의 품질 경고 절: 해설
 * 400~700자를 적어 뒀는데 5개 전부 359~395자로 나오고도 아무도 몰랐다). 그래서 근거를
 * 스키마 필드로 받아({@code GeneratedProblemItem.sourceQuote}) 기계가 대조한다.
 *
 * <p><b>왜 {@link ProblemItemRule}에 넣지 않았나.</b> 저쪽은 "생성 배치와 흡수가 공유하는"
 * 판정이라 항목 하나만 보면 되는데, 이 검사는 <b>근거 문서를 손에 들고 있어야</b> 한다.
 * 흡수({@code LlmProblemService})는 파일만 받으므로 대조할 원본이 없다. 부를 수 없는 곳이
 * 있는 규칙을 같은 문에 넣으면 "왜 흡수는 이 경고를 안 내지"라는 혼란이 남는다.
 *
 * <h2>판정 방향: "지목 절 안인가"가 아니라 "남의 절을 캤는가"</h2>
 *
 * <p>처음 떠오르는 설계는 "오늘 난이도가 지목한 절 안에서 인용했는가"다. 그런데 그러면
 * <b>중급이 매번 걸린다</b>. 중급이 지목하는 {@code ### 왜 이렇게 설계됐는가}는 본론 절
 * <b>안에</b> 있는 소제목이고, 문서 프롬프트는 설계 근거를 본문 문장으로도 녹여 쓰게 한다
 * ({@code ClaudeProblemGenerator.SOURCE_SECTIONS} 주석의 "중급에 두 절을 적은 이유").
 * 정상 동작에 매번 울리는 경고는 다음부터 아무도 안 본다.
 *
 * <p>그런데 정작 막고 싶은 사고는 그 방향이 아니었다. 2026-08-14에 난 일은 <b>중급이 고급
 * 전용 절을 미리 캐서 다음 날 재료가 마른 것</b>이다. 그러니 잣대도 그쪽으로 대면 된다 —
 * 도입부·용어표·본론처럼 <b>어느 난이도의 것도 아닌 구간</b>은 봐주고, 다른 난이도가 쓸
 * 절을 캤을 때만 알린다. 오탐이 적고, 막으려던 사고는 그대로 잡힌다.
 *
 * <p><b>차단하지 않고 알리기만 한다.</b> 인용은 문자열 대조로 재는데, 모델이 조사 하나를
 * 바꾸거나 줄을 눌러 붙이면 못 찾는다. 정규화로 상당 부분 흡수하지만 완전할 수 없고,
 * 못 찾았다고 멀쩡한 문제를 요금까지 내고 버리는 것은 손해다({@code ProblemItemRule}의
 * "버릴 것과 알릴 것" 구분과 같은 판단).
 */
public final class SourceQuoteRule {

    /** 로그 한 줄로 읽히게 자를 길이. 어느 문장을 인용했는지 알아볼 정도면 충분하다. */
    private static final int SNIPPET_LENGTH = 40;

    /** 마크다운의 2단계 절 제목. {@code ### }는 잡지 않는다 — 본론 안의 소제목은 절이 아니다. */
    private static final Pattern SECTION_HEADING = Pattern.compile("^## (?!#).*$", Pattern.MULTILINE);

    /** 줄바꿈·탭·연속 공백을 한 칸으로 누르는 잣대 — 정규화의 전부다. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private SourceQuoteRule() {
    }

    /**
     * 근거 인용의 문제를 <b>사람이 읽을 수 있는 한 줄</b>로 돌려준다. 멀쩡하면 {@code null}.
     *
     * @param doc        근거 문서. {@code null}이면(폴백으로 돈 날) 검사하지 않는다 —
     *                   대조할 원본이 없는데 경고를 내면 그날 치 전부에 헛울린다
     * @param difficulty 오늘의 난이도. 어느 절이 "남의 것"인지가 여기서 갈린다
     */
    public static String warningOf(GeneratedProblemItem item, SourceDocument doc, Difficulty difficulty) {
        if (doc == null) {
            return null;
        }

        String quote = item.sourceQuote();
        if (quote == null || quote.isBlank()) {
            return "근거 인용이 비어 있음 (문서에서 어느 문장을 근거로 삼았는지 알 수 없다)";
        }

        String needle = normalize(quote);
        String haystack = normalize(doc.contentMd());
        if (!haystack.contains(needle)) {
            return "근거 인용을 문서에서 찾지 못함 (\"%s\" — 문서 밖에서 끌어왔을 수 있다)"
                    .formatted(snippet(quote));
        }

        // 업로드 문서에는 약속된 절이 없다. 절 이름을 모르는 문서에 침범 잣대를 대면
        // 나오는 것은 결함이 아니라 잡음이다 — sourceFocus가 업로드 경로를 가른 것과 같은 판단.
        if (doc.kind() == SourceDocument.Kind.UPLOADED) {
            return null;
        }

        String trespassed = trespassedSectionOf(needle, doc.contentMd(), difficulty);
        return trespassed == null ? null
                : "%s가 다른 난이도의 절에서 캠 (\"%s\" — 그 날 재료를 미리 쓴다)"
                        .formatted(displayName(difficulty), trespassed);
    }

    /**
     * 인용이 <b>다른 난이도의 전용 절</b> 안에 있으면 그 절 제목을, 아니면 {@code null}.
     *
     * <p>본문을 {@code ## } 제목마다 잘라 구간을 만들고, 인용이 통째로 들어 있는 구간을 찾는다.
     * 구간 경계를 걸쳐 인용한 경우는 어느 구간에도 안 잡히는데, 그건 봐준다 — 여러 절을 엮어
     * 낸 문제를 "침범"이라 부를 근거가 없다.
     */
    private static String trespassedSectionOf(String needle, String contentMd, Difficulty difficulty) {
        Set<String> mine = Set.copyOf(ClaudeProblemGenerator.SOURCE_SECTIONS
                .getOrDefault(difficulty, List.of()));

        var matcher = SECTION_HEADING.matcher(contentMd);
        String heading = null;
        int bodyFrom = 0;
        while (matcher.find()) {
            // 직전 제목이 이끄는 구간(제목 끝 ~ 이번 제목 시작)이 인용을 품는지 본다
            String owner = ownerOf(heading, contentMd, bodyFrom, matcher.start(), needle, mine);
            if (owner != null) {
                return owner;
            }
            heading = matcher.group().trim();
            bodyFrom = matcher.end();
        }
        // 마지막 절은 다음 제목이 없으므로 문서 끝까지가 구간이다
        return ownerOf(heading, contentMd, bodyFrom, contentMd.length(), needle, mine);
    }

    /**
     * 구간 {@code [from, to)}가 인용을 품고 있고 그 절이 <b>남의 것</b>이면 절 제목을 돌려준다.
     *
     * <p>{@code heading}이 {@code null}인 첫 구간(문서 제목과 머리말)은 어느 난이도의 것도
     * 아니므로 항상 통과시킨다.
     */
    private static String ownerOf(String heading, String contentMd, int from, int to,
                                  String needle, Set<String> mine) {
        if (heading == null || mine.contains(heading)) {
            return null;
        }
        boolean isSomeoneElses = ClaudeProblemGenerator.SOURCE_SECTIONS.values().stream()
                .flatMap(List::stream)
                .anyMatch(heading::equals);
        if (!isSomeoneElses) {
            return null; // 본론·도입부·요약처럼 아무도 지목하지 않은 구간은 자유롭게 쓴다
        }
        return normalize(contentMd.substring(from, to)).contains(needle) ? heading : null;
    }

    /**
     * 줄바꿈과 연속 공백을 한 칸으로 누른다 — 이 검사가 쓸 만해지는 유일한 이유.
     *
     * <p>모델은 "그대로 옮겨라"라는 지시를 받아도 문서의 줄바꿈을 눌러 한 줄로 붙이거나
     * 들여쓰기를 흘린다. 그때마다 "문서 밖"이라 부르면 경고가 일상이 되고, 일상이 된 경고는
     * 없는 것만 못하다({@code DocumentDraftValidator.WARN_LENGTH} 주석과 같은 실패 방식).
     *
     * <p>여기까지만 하고 조사·어미까지 맞춰 주지는 않는다. 그건 이미 "그대로 옮겼는가"가
     * 아니라 "비슷한가"를 재는 것이고, 비슷함의 기준을 정하는 순간 이 검사는 판정을 못 한다.
     */
    private static String normalize(String text) {
        return WHITESPACE.matcher(text).replaceAll(" ").trim();
    }

    /** 경고 한 줄에 실을 인용 앞부분. */
    private static String snippet(String quote) {
        String flat = normalize(quote);
        return flat.length() > SNIPPET_LENGTH ? flat.substring(0, SNIPPET_LENGTH) + "…" : flat;
    }

    /** 경고 문구에 쓸 난이도 이름 — "INTERMEDIATE가"보다 "중급이"가 읽힌다. */
    private static String displayName(Difficulty difficulty) {
        return difficulty == null ? "이 문제" : switch (difficulty) {
            case BEGINNER -> "초급";
            case INTERMEDIATE -> "중급";
            case ADVANCED -> "고급";
        };
    }
}
