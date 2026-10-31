package project.study.study_project.llm.support;

import java.util.List;

/**
 * 대기열 메모를 모델용 작성 지시로 읽은 것 — 2026-10-29.
 *
 * <p>메모는 "핵심 개념:", "설명할 것:", "다루지 않을 것:", "실물:", "그림:" 이름표를 단 줄로 쓴다.
 * 앞의 두 칸이 모두 있어야 지시로 본다. 이름표 없는 옛 메모는 사람용 작업 기록이라
 * (대기열 id·날짜가 섞여 있다) 모델에 실으면 문서로 새어 나온다.
 *
 * <p>칸 정의와 이유는 docs/27-topic-roadmap.md에 있다.
 */
public record TopicBrief(String concepts, String questions, String outOfScope, String facts,
                         String diagram, List<String> laterTopics) {

    private static final String CONCEPTS = "핵심 개념:";
    private static final String QUESTIONS = "설명할 것:";
    private static final String OUT_OF_SCOPE = "다루지 않을 것:";
    private static final String FACTS = "실물:";
    private static final String DIAGRAM = "그림:";
    private static final String LATER = "뒤 편이 다룰 주제:";

    public TopicBrief {
        laterTopics = laterTopics == null ? List.of() : List.copyOf(laterTopics);
    }

    /**
     * 같은 분야 블록에서 이 편 뒤에 오는 주제들 — 심화편 전용.
     * 심화편은 질문 목록을 받지 않아 범위가 넓어지기 쉽고, 실제로 뒤 편 주제를 미리 썼다(2026-10-29).
     */
    public TopicBrief withLaterTopics(List<String> later) {
        return new TopicBrief(concepts, questions, outOfScope, facts, diagram, later);
    }

    /** 형식을 갖춘 메모면 지시를, 아니면 {@code null}을 돌려준다. */
    public static TopicBrief parse(String memo) {
        if (memo == null || memo.isBlank()) {
            return null;
        }
        String concepts = null, questions = null, outOfScope = null, facts = null, diagram = null;
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
            } else if (line.startsWith(DIAGRAM)) {
                diagram = value(line, DIAGRAM);
            }
        }
        if (concepts == null || questions == null) {
            return null;
        }
        return new TopicBrief(concepts, questions, outOfScope, facts, diagram, List.of());
    }

    private static String value(String line, String label) {
        String v = line.substring(label.length()).strip();
        return v.isEmpty() ? null : v;
    }

    /** 입문편에 싣는 줄 — 메모의 다섯 칸 전부. */
    public String forBeginner() {
        StringBuilder sb = new StringBuilder();
        append(sb, CONCEPTS, concepts);
        append(sb, QUESTIONS, questions);
        append(sb, OUT_OF_SCOPE, outOfScope);
        append(sb, FACTS, facts);
        append(sb, DIAGRAM, diagram);
        return sb.toString();
    }

    /**
     * 심화편에 싣는 줄. 질문 목록을 주면 입문편과 같은 글이 한 번 더 나오고,
     * 그림 지정을 주면 입문편이 그린 그림을 다시 그린다.
     */
    public String forAdvanced() {
        StringBuilder sb = new StringBuilder();
        append(sb, CONCEPTS, concepts);
        append(sb, OUT_OF_SCOPE, outOfScope);
        append(sb, FACTS, facts);
        append(sb, LATER, laterTopics.isEmpty() ? null : String.join(", ", laterTopics));
        return sb.toString();
    }

    private static void append(StringBuilder sb, String label, String value) {
        if (value != null) {
            sb.append(label).append(' ').append(value).append('\n');
        }
    }
}
