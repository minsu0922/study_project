package project.study.study_project.llm.support;

/**
 * 대기열 메모를 모델용 작성 지시로 읽은 것 — 2026-10-29.
 *
 * <p>메모는 "핵심 개념:", "설명할 것:", "다루지 않을 것:", "실물:" 이름표를 단 줄로 쓴다.
 * 앞의 두 칸이 모두 있어야 지시로 본다. 이름표 없는 옛 메모는 사람용 작업 기록이라
 * (대기열 id·날짜가 섞여 있다) 모델에 실으면 문서로 새어 나온다.
 *
 * <p>칸 정의와 이유는 docs의 주제 로드맵 문서에 있다.
 */
public record TopicBrief(String concepts, String questions, String outOfScope, String facts) {

    private static final String CONCEPTS = "핵심 개념:";
    private static final String QUESTIONS = "설명할 것:";
    private static final String OUT_OF_SCOPE = "다루지 않을 것:";
    private static final String FACTS = "실물:";

    /** 형식을 갖춘 메모면 지시를, 아니면 {@code null}을 돌려준다. */
    public static TopicBrief parse(String memo) {
        if (memo == null || memo.isBlank()) {
            return null;
        }
        String concepts = null, questions = null, outOfScope = null, facts = null;
        for (String raw : memo.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith(CONCEPTS)) {
                concepts = value(line, CONCEPTS);
            } else if (line.startsWith(QUESTIONS)) {
                questions = value(line, QUESTIONS);
            } else if (line.startsWith(OUT_OF_SCOPE)) {
                outOfScope = value(line, OUT_OF_SCOPE);
            } else if (line.startsWith(FACTS)) {
                facts = value(line, FACTS);
            }
        }
        if (concepts == null || questions == null) {
            return null;
        }
        return new TopicBrief(concepts, questions, outOfScope, facts);
    }

    private static String value(String line, String label) {
        String v = line.substring(label.length()).strip();
        return v.isEmpty() ? null : v;
    }

    /** 입문편에 싣는 줄 — 네 칸 전부. */
    public String forBeginner() {
        StringBuilder sb = new StringBuilder();
        append(sb, CONCEPTS, concepts);
        append(sb, QUESTIONS, questions);
        append(sb, OUT_OF_SCOPE, outOfScope);
        append(sb, FACTS, facts);
        return sb.toString();
    }

    /** 심화편에 싣는 줄. 질문 목록을 주면 입문편과 같은 글이 한 번 더 나온다. */
    public String forAdvanced() {
        StringBuilder sb = new StringBuilder();
        append(sb, CONCEPTS, concepts);
        append(sb, OUT_OF_SCOPE, outOfScope);
        append(sb, FACTS, facts);
        return sb.toString();
    }

    private static void append(StringBuilder sb, String label, String value) {
        if (value != null) {
            sb.append(label).append(' ').append(value).append('\n');
        }
    }
}
