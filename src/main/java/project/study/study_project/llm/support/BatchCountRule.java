package project.study.study_project.llm.support;

import project.study.study_project.global.common.Difficulty;

import java.util.EnumMap;
import java.util.Map;

/**
 * 오늘 난이도가 <b>몇 문제를 만드는가</b> — 배치와 관리자 화면이 공유하는 규칙(2026-09-05 신설).
 *
 * <h2>왜 난이도마다 개수를 달리하나</h2>
 *
 * <p>4일 주기(문서·초·중·고)가 난이도마다 {@code batch-count}를 똑같이 5로 써서 초·중·고급이
 * 정확히 1:1:1로 쌓였다. 사용자가 원하는 비율은 그게 아니다 — <b>초급이 가장 많고 고급이 가장
 * 적어야</b> 한다. 처음 보는 개념은 용어부터 여러 번 만나야 하고, 고급은 한 주제에 두세 문제면
 * 충분하기 때문이다.
 *
 * <p><b>재료 실측이 이 방향을 뒷받침한다(2026-09-05).</b> 문서 6편의 {@code ## 용어 한눈에}에는
 * 용어가 13~15개씩 있는데 초급 5문제가 실제로 쓴 것은 0~5개였다 — {@code xss-context-aware-
 * output-encoding}은 용어표 13개를 <b>하나도</b> 쓰지 않고 본문에서만 뽑았다. 초급 재료는
 * 남아돈다. 반대로 고급은 이미 5개를 못 채우는 날이 있었다(2026-08-14 3개, 09-23 4개,
 * 같은 문서로 두 번째 고급을 뽑은 10-05가 3개). 개수를 7·5·3으로 두면 비율이 사용자가
 * 원하는 모양이 되면서 <b>고급 쪽 수확량 경고도 함께 줄어든다</b>.
 *
 * <h2>왜 하루에 두 난이도를 섞지 않았나</h2>
 *
 * <p>처음 검토한 안은 "고급 날에 고급 2개 + 초급 3개"였다. 비율(8:5:2)은 더 가파르지만
 * 구조를 통째로 건드린다 — 초급과 고급은 <b>읽는 문서가 다르다</b>
 * ({@code DraftGeneratorCli.editionFor}: 고급은 심화편, 초급·중급은 입문편). 한 날에 섞으면
 * 문서 2편·프롬프트 2벌·API 2회가 되고, {@code GeneratedBatchFile}이 난이도 한 칸에 문제 한
 * 목록이라 파일 스키마·파일명 규칙·들여오기 경로·수확량 점검이 전부 "하루 = 한 난이도"라는
 * 전제 위에 있다. 개수만 바꾸면 <b>그 전제를 하나도 건드리지 않고</b> 같은 목적을 이룬다.
 *
 * <h2>왜 문자열 하나로 설정하나</h2>
 *
 * <p>{@code BEGINNER=7,INTERMEDIATE=5,ADVANCED=3} 한 줄이다. 중첩 맵으로 두면 배치({@code
 * DraftGeneratorCli}가 SnakeYAML로 직접 읽는다)와 앱({@code @Value})이 서로 다른 방식으로
 * 꺼내게 되고, 그러면 <b>파싱이 두 벌</b>이 된다. 이 저장소가 반복해 겪은 사고가 정확히 그것이라
 * ({@code ProblemItemRule} 클래스 주석) 양쪽 다 문자열로 받아 <b>이 클래스 하나</b>에 넘긴다.
 * {@code batch-domains}가 이미 같은 방식이라 설정 파일의 모양도 어긋나지 않는다.
 *
 * <p><b>값이 없거나 망가지면 폴백으로 간다.</b> 설정 오타 하나로 그날 배치가 통째로 죽는 것보다
 * 예전 개수로라도 도는 편이 낫다 — {@code batch-enabled}가 없을 때 켜진 것으로 보는 판단과 같다.
 * 다만 <b>범위를 벗어난 숫자는 던진다.</b> 그건 오타가 아니라 요금이 걸린 값이고,
 * {@link GenerationLimits} 주석이 말하듯 조용히 잘라 쓰면 사람은 자기가 적은 값이 나온 줄 안다.
 */
public final class BatchCountRule {

    /**
     * 설정이 없을 때 쓰는 기본 배분 — <b>{@code application.yml}의 값과 같아야 한다.</b>
     *
     * <p>기본값을 코드에도 두는 것은 {@code batch-domains}가 이미 쓰는 방식이다
     * ({@code AdminBatchService}의 {@code @Value} 기본값 주석). 설정 파일이 진짜 출처이고
     * 이 문자열은 <b>설정 키가 통째로 사라졌을 때의 그물</b>이다.
     */
    public static final String DEFAULT_SPEC = "BEGINNER=7,INTERMEDIATE=5,ADVANCED=3";

    private BatchCountRule() {
    }

    /**
     * 배분 문자열을 난이도별 개수로 읽는다. 못 읽는 항목은 <b>조용히 건너뛴다</b>.
     *
     * <p>항목 하나가 망가졌다고 나머지 둘까지 버릴 이유는 없다 — 그날 배치가 죽는 대신
     * 그 난이도만 폴백을 쓰면 된다. 반면 <b>숫자가 범위를 벗어나면 던진다</b>(클래스 주석).
     *
     * @param spec {@code BEGINNER=7,INTERMEDIATE=5,ADVANCED=3} 꼴. {@code null}이면 빈 맵
     * @throws IllegalArgumentException 적힌 숫자가 {@link GenerationLimits} 범위 밖일 때
     */
    public static Map<Difficulty, Integer> parse(String spec) {
        Map<Difficulty, Integer> counts = new EnumMap<>(Difficulty.class);
        if (spec == null || spec.isBlank()) {
            return counts;
        }
        for (String entry : spec.split(",")) {
            String[] pair = entry.split("=", 2);
            if (pair.length != 2) {
                continue; // "BEGINNER" 처럼 등호가 빠진 조각 — 그 난이도만 폴백으로 간다
            }
            Difficulty difficulty;
            try {
                difficulty = Difficulty.valueOf(pair[0].trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                continue; // 없는 난이도 이름. 오타 하나로 배치를 죽이지 않는다
            }
            int count;
            try {
                count = Integer.parseInt(pair[1].trim());
            } catch (NumberFormatException e) {
                continue; // 숫자가 아니면 그 항목만 버린다
            }
            // 숫자로 읽히기는 했는데 범위를 벗어난 경우는 다르다. 이건 "못 알아들었다"가 아니라
            // <사람이 요금이 걸린 값을 잘못 적었다>는 뜻이라, 호출 전에 빨간불로 알려야 한다.
            requireInRange(count, "llm.generation.batch-count-by-difficulty의 " + difficulty);
            counts.put(difficulty, count);
        }
        return counts;
    }

    /**
     * 오늘 만들 개수 — <b>난이도별 값이 있으면 그것, 없으면 {@code fallback}</b>.
     *
     * <p>난이도가 {@code null}인 경우(문서일, 또는 난이도를 안 넘긴 옛 호출)에도 폴백을 준다.
     * 여기서 예외를 던지면 개수와 아무 상관 없는 경로가 이 규칙 때문에 죽는다.
     *
     * @param spec       배분 문자열
     * @param difficulty 오늘 난이도. {@code null}이면 폴백
     * @param fallback   난이도별 값이 없을 때 쓸 개수({@code batch-count})
     */
    public static int countFor(String spec, Difficulty difficulty, int fallback) {
        if (difficulty == null) {
            return fallback;
        }
        Integer count = parse(spec).get(difficulty);
        return count == null ? fallback : count;
    }

    /**
     * 요금이 걸린 숫자를 범위 안에서만 받는다 — 벗어나면 <b>어디를 고쳐야 하는지</b> 적어 던진다.
     *
     * <p>{@code DraftGeneratorCli.resolveCount}가 같은 검사를 하고 있었는데, 설정이 두 갈래로
     * 늘면서 검사도 두 벌이 될 참이었다. 출처 이름만 인자로 받고 판정은 한 곳에 둔다.
     *
     * @param source 값이 어디서 왔는지 — 오류 메시지에 그대로 실린다
     */
    public static void requireInRange(int count, String source) {
        if (count < GenerationLimits.MIN_COUNT || count > GenerationLimits.MAX_COUNT) {
            throw new IllegalArgumentException("%s는 %d~%d 사이여야 합니다(받은 값: %d)".formatted(
                    source, GenerationLimits.MIN_COUNT, GenerationLimits.MAX_COUNT, count));
        }
    }
}
