package project.study.study_project.llm.review;

import project.study.study_project.llm.client.GeneratedProblemItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * 검수에 보여 줄 문제 하나. order[n]은 화면 n+1번 보기가 원래 몇 번째 보기인지다.
 * 생성기가 정답을 늘 첫 보기에 두므로, 섞지 않으면 위치만으로 답을 고를 수 있다.
 *
 * @param index 검수에 넘긴 전체 문제 목록 안의 위치
 */
public record Shown(int index, GeneratedProblemItem item, List<Integer> order) {

    private static final Pattern CHOICE_NO = Pattern.compile("(?<![0-9])([1-4])번");

    /** 보기 4개·정답 1개인 객관식만 고른다. 질문으로 씨앗을 정해 같은 문제는 늘 같은 순서로 섞는다 */
    public static List<Shown> showable(List<GeneratedProblemItem> problems) {
        List<Shown> shown = new ArrayList<>();
        for (int i = 0; i < problems.size(); i++) {
            GeneratedProblemItem p = problems.get(i);
            if (p.question() == null || p.question().isBlank() || p.choices() == null || p.choices().size() != 4
                    || p.choices().stream().filter(GeneratedProblemItem.GeneratedChoice::correct).count() != 1) {
                continue;
            }
            List<Integer> order = new ArrayList<>(List.of(0, 1, 2, 3));
            Collections.shuffle(order, new Random(p.question().hashCode()));
            shown.add(new Shown(i, p, order));
        }
        return shown;
    }

    /** 섞인 화면에서 정답의 번호(1부터) */
    public int correctNo() {
        for (int n = 0; n < order.size(); n++) {
            if (item.choices().get(order.get(n)).correct()) {
                return n + 1;
            }
        }
        return 0;
    }

    /** 섞인 화면 번호(1부터)의 보기 글. 범위 밖이면 빈 문자열 */
    public String choiceText(int no) {
        return no >= 1 && no <= order.size() ? item.choices().get(order.get(no - 1)).text() : "";
    }

    /**
     * AI가 쓴 글 속 "N번"을 원래 보기 순서의 번호로 바꾼다. AI는 섞인 순서로 봤으므로
     * 그 번호는 검수함에 보이는 번호가 아니다. 횟수를 뜻하는 "3번 재전송"도 바뀌지만 가를 방법이 없다.
     */
    public String inOriginalNumbers(String text) {
        if (text == null) {
            return "";
        }
        return CHOICE_NO.matcher(text.strip()).replaceAll(
                m -> (order.get(Integer.parseInt(m.group(1)) - 1) + 1) + "번");
    }
}
