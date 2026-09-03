package project.study.study_project.llm.support;

import project.study.study_project.global.common.ProblemType;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 근거 문서에 <b>이 유형을 낼 재료가 있는지</b>를 API 호출 <b>전에</b> 판정한다 — 2026-09-01 신설.
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>난이도에는 이미 같은 장치가 있다({@code DraftGeneratorCli.hasMaterialFor}). "오늘 중급인데
 * 이 문서에 중급이 캘 절이 없다"를 미리 보고 폴백하거나 멈춘다. 유형에는 그게 없었다. 그래서
 * <b>표가 통째로 없는 문서</b>로 짝짓기를 뽑으라고 시키면, 모델은 멈추지 않고 문서 바깥의 제
 * 지식으로 네 쌍을 지어낸다. 결과는 요금을 다 낸 뒤에야 알 수 있고, 그 문제는 "근거 문서를 읽고
 * 푸는" 이 사이트의 전제와도 어긋난다.
 *
 * <h2>무엇을 세는가 — 그리고 이게 왜 대리 지표인가</h2>
 *
 * <p>정말 알고 싶은 것은 "같은 갈래의 개념 넷이 있고 그 설명이 서로 배타적인가"다. 그건 뜻을
 * 읽어야 아는 것이라 코드로 잴 수 없다. 대신 <b>표의 데이터 행 수</b>를 센다. 행이 넷 미만이면
 * 재료가 없는 것이 <b>확실하고</b>, 넷 이상이라고 좋은 문제가 보장되지는 <b>않는다</b>. 이 검사가
 * 막는 것은 "재료가 아예 없는 문서에 요금을 쓰는 것" 하나뿐이고, 나머지(오른쪽 중복, 빈 쌍,
 * 왼쪽 용어 반복)는 이미 {@link ProblemItemRule}의 짝짓기 검사와 검수자가 잡는다.
 *
 * <h2>왜 두 절을 함께 세는가</h2>
 *
 * <p>프롬프트는 짝짓기 재료로 {@code ## 바탕이 되는 개념}의 표를 지목한다. 그런데 2026-09-01에
 * 실물 문서 10편을 세어 보니 그 절에 표가 있는 문서는 <b>1편</b>뿐이었고, 나머지의 재료는 전부
 * {@code ### 용어 한눈에}에 있었다(7~15행). 지목한 자리가 비면 모델은 알아서 다른 절을 캐므로
 * 문제는 나오지만, <b>검사가 지목만 따라가면 멀쩡한 문서 아홉 편을 막는다.</b> 그래서 판정은 두
 * 절의 합으로 하고, 프롬프트의 지목도 같은 날 두 절로 고쳤다.
 *
 * <p><b>문서 전체의 표를 세지 않는 이유</b>: 뒤쪽 절에는 설정값 표·증상 표가 섞여 있어 "짝지을
 * 개념"이 아닌 행까지 세어진다. 오탐이 잦아지면 검사가 있으나 마나가 된다 — 좁게 시작하고,
 * 막지 말아야 할 문서를 실제로 막는 것을 보면 그때 절을 하나 늘린다.
 */
public final class TypeMaterialRule {

    /**
     * 짝짓기에 필요한 최소 표 행 수.
     *
     * <p>프롬프트가 <b>정확히 네 쌍</b>을 요구하므로({@code ClaudeProblemGenerator}의 [짝짓기
     * 문제의 조건]) 재료도 최소 넷이어야 한다. {@link ProblemItemRule#MIN_CHOICES}(=2)를 쓰지
     * 않는 것은 그 값이 <b>나온 결과를 버릴지</b> 정하는 기준이라서다. 여기는 <b>만들기 전에</b>
     * 재료를 보는 자리라 요구치가 더 높다 — 둘을 같은 상수로 묶으면 한쪽을 고칠 때 다른 쪽이
     * 조용히 따라 움직인다.
     */
    public static final int MATCHING_MIN_ROWS = 4;

    /** 짝짓기 재료가 실제로 놓이는 자리. 위 클래스 주석의 실측 근거 참고. */
    // 용어 표는 편마다 수준이 다르다 — 입문편은 최상위 절(2026-09-03 후속 개정에서 승격),
    // 심화편은 본론 끝의 소제목이다. 둘 다 넣지 않으면 한쪽 편의 표를 통째로 못 센다.
    // 실제로 승격한 날 이 목록을 안 고쳐서 "짝짓기 재료 0행" 경고가 멀쩡한 문서에 떴다.
    private static final List<String> MATCHING_SECTIONS =
            List.of("## 바탕이 되는 개념", "## 용어 한눈에", "### 용어 한눈에");

    /** 코드블록 안의 {@code |}는 표가 아니다 — 셈에서 먼저 지운다. */
    private static final Pattern FENCED_CODE = Pattern.compile("(?s)```.*?```");

    /** 마크다운 표의 한 줄: 양끝이 파이프. */
    private static final Pattern TABLE_ROW = Pattern.compile("(?m)^\\s*\\|.*\\|\\s*$");

    /** 표의 구분선({@code |---|---|}) — 데이터 행이 아니다. */
    private static final Pattern TABLE_DIVIDER = Pattern.compile("^\\s*\\|[\\s:\\-|]+\\|\\s*$");

    /** 제목 줄과 그 수준(#의 개수). */
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");

    private TypeMaterialRule() {
    }

    /**
     * 이 문서로 그 유형을 낼 수 없으면 <b>사유</b>를, 낼 수 있으면 {@code null}을 돌려준다.
     *
     * <p>반환 형태를 {@code boolean}이 아니라 사유 문자열로 잡은 것은 {@link ProblemItemRule}의
     * {@code defectOf}와 같은 규약이다. 부르는 쪽이 "왜 막혔는지"를 그대로 로그와 실패 메시지에
     * 실어야 하는데, boolean을 받으면 사유를 부르는 쪽에서 다시 지어내게 된다 — 그러면 같은 말이
     * 두 곳에 생기고 언젠가 어긋난다.
     *
     * <p><b>판단 근거가 없으면 막지 않는다.</b> 문서가 {@code null}이거나(폴백으로 도는 날) 유형이
     * 짝짓기가 아니면 통과다. 확신 없이 버리는 쪽이 더 나쁘다는 판단은 난이도 검사와 같다.
     *
     * <p><b>순서 배열을 일부러 뺐다.</b> "단계가 넷 이상인가"는 표처럼 셀 수 없고, 절차가 없는
     * 주제에 절을 강요하면 모델이 절을 지어낸다. 그건 재료를 보장하는 것이 아니라 <b>재료가 있는
     * 척</b>을 보장하는 것이다. 순서 배열은 사람이 문서를 보고 고르는 쪽으로 남긴다.
     *
     * @param contentMd 근거 문서 본문(마크다운). {@code null}이면 검사하지 않는다
     * @param type      만들려는 문제 유형
     * @return 막을 사유, 없으면 {@code null}
     */
    public static String missingMaterialOf(String contentMd, ProblemType type) {
        if (contentMd == null || type != ProblemType.MATCHING) {
            return null;
        }

        int rows = comparableRowsOf(contentMd);
        if (rows >= MATCHING_MIN_ROWS) {
            return null;
        }
        return "짝짓기 재료가 없습니다 — %s의 표가 %d행이라 %d쌍을 만들 수 없습니다"
                .formatted(String.join("·", MATCHING_SECTIONS), rows, MATCHING_MIN_ROWS);
    }

    /**
     * 짝짓기 재료가 되는 절들의 <b>표 데이터 행</b> 수를 센다(머리글·구분선 제외).
     *
     * <p>{@code public}인 이유: 문서 검증기가 같은 수를 세어 검수자에게 경고를 띄운다
     * ({@link DocumentDraftValidator}). 세는 방법이 두 곳에 따로 있으면 "검증은 통과했는데
     * 생성에서 막히는" 어긋남이 난다.
     */
    public static int comparableRowsOf(String contentMd) {
        if (contentMd == null || contentMd.isBlank()) {
            return 0;
        }
        // 코드블록을 먼저 지운다. 로그 예시나 표 문법을 설명하는 코드가 표로 세어지면
        // 재료가 없는 문서가 통과한다 — 검사가 있으나 마나가 되는 가장 흔한 경로다.
        String body = FENCED_CODE.matcher(contentMd).replaceAll("");

        int total = 0;
        for (String heading : MATCHING_SECTIONS) {
            total += dataRowsOf(sectionOf(body, heading));
        }
        return total;
    }

    /**
     * 제목 한 줄에 딸린 본문을 잘라 낸다 — 다음에 나오는 <b>같거나 더 높은 수준</b>의 제목 전까지.
     *
     * <p>수준을 보는 이유: {@code ## 바탕이 되는 개념} 안에 {@code ### 하위 제목}이 있어도 그건
     * 여전히 그 절의 몫이다. 제목이면 무조건 끊으면 절이 첫 소제목에서 잘려 표를 놓친다.
     *
     * <p><b>다른 대상 절을 만나도 끊는다.</b> 수준만 보면 {@code ### 용어 한눈에}가
     * {@code ## 바탕이 되는 개념} 안에 놓인 문서에서 <b>같은 표를 두 번 센다</b>. 합계가 부풀면
     * 재료가 얇은 문서가 통과하는데, 그게 이 검사가 막으려던 바로 그 경우다. 두 구역이 절대
     * 겹치지 않게 잘라 낸다.
     */
    private static String sectionOf(String body, String heading) {
        int level = heading.indexOf(' '); // "##" → 2, "###" → 3
        StringBuilder out = new StringBuilder();
        boolean inside = false;

        for (String line : body.split("\n", -1)) {
            String stripped = line.strip();
            if (HEADING.matcher(stripped).matches()) {
                boolean anotherTarget = !stripped.equals(heading) && MATCHING_SECTIONS.contains(stripped);
                if (inside && (headingLevelOf(stripped) <= level || anotherTarget)) {
                    break;
                }
                if (stripped.equals(heading)) {
                    inside = true;
                    continue;
                }
            }
            if (inside) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    /** 제목 줄의 {@code #} 개수. 호출 전에 {@link #HEADING} 일치를 확인한 줄만 넘어온다. */
    private static int headingLevelOf(String headingLine) {
        int level = 0;
        while (level < headingLine.length() && headingLine.charAt(level) == '#') {
            level++;
        }
        return level;
    }

    /**
     * 표의 데이터 행 수. 파이프로 둘러싸인 줄에서 구분선을 빼고, 남은 것의 <b>첫 줄은 머리글</b>이라
     * 하나를 더 뺀다.
     *
     * <p>한 절에 표가 둘 이상이면 머리글도 둘이지만 하나만 뺀다 — 한 행쯤 넉넉히 세는 오차는
     * "재료가 아예 없는 문서"를 걸러내는 이 검사의 목적을 해치지 않는다. 반대로 표마다 정확히
     * 빼려고 표의 시작을 추적하면 코드가 길어지는데, 그 복잡도가 막아 주는 사고는 없다.
     */
    private static int dataRowsOf(String section) {
        if (section.isBlank()) {
            return 0;
        }
        int rows = 0;
        var matcher = TABLE_ROW.matcher(section);
        while (matcher.find()) {
            if (!TABLE_DIVIDER.matcher(matcher.group()).matches()) {
                rows++;
            }
        }
        return Math.max(0, rows - 1);
    }
}
