package project.study.study_project.llm.support;

import java.util.List;

/**
 * 대기열 메모를 모델용 작성 지시로 읽은 것 — 2026-10-29.
 *
 * <p>메모는 "핵심 개념:", "다루지 않을 것:", "실물:", "그림:" 이름표를 단 줄로 쓴다.
 * "핵심 개념"이 있어야 지시로 본다. 이름표 없는 옛 메모는 사람용 작업 기록이라
 * (대기열 id·날짜가 섞여 있다) 모델에 실으면 문서로 새어 나온다.
 *
 * <p>"설명할 것:"(질문 목록)은 2026-11-12부터 사람이 쓰는 입문편 「글의 주제」가 맡는다.
 * 두 곳에 질문이 있으면 어긋날 때 모델이 어느 쪽을 따를지 모른다. 글의 주제를 저장하면
 * 그 주제의 이 줄은 지운다. 아직 안 옮긴 메모를 위해 읽기만은 남겨 둔다.
 *
 * <p>칸 정의와 이유는 docs/27-topic-roadmap.md에 있다.
 */
public record TopicBrief(String concepts, String questions, String outOfScope, String facts,
                         String diagram, List<String> laterTopics,
                         String beginnerTopicBox, String advancedTopicBox) {

    /** 글의 주제(V48)가 없는 지시 — 메모만 있는 경우와 V48 전의 호출부가 쓴다. */
    public TopicBrief(String concepts, String questions, String outOfScope, String facts,
                      String diagram, List<String> laterTopics) {
        this(concepts, questions, outOfScope, facts, diagram, laterTopics, null, null);
    }

    /** 사람이 쓴 글의 주제를 얹은 사본. 메모가 지시 형식이 아니면 {@code brief}가 없어 글만 담는다 */
    public static TopicBrief withTopicBoxes(TopicBrief brief, String beginner, String advanced) {
        if (beginner == null && advanced == null) {
            return brief;
        }
        TopicBrief base = brief != null ? brief : new TopicBrief(null, null, null, null, null, List.of());
        return new TopicBrief(base.concepts, base.questions, base.outOfScope, base.facts, base.diagram,
                base.laterTopics, beginner, advanced);
    }

    /** 메모가 지시 형식이라 [작성 지시]로 실을 칸이 있는가. 글의 주제만 있는 사본은 아니다 */
    public boolean hasMemoFields() {
        return concepts != null;
    }

    /** 메모에서 "설명할 것:" 줄을 뺀다 — 질문은 글의 주제로 옮겼다. 남는 것이 없으면 {@code null} */
    public static String withoutQuestions(String memo) {
        if (memo == null) {
            return null;
        }
        String kept = memo.lines()
                .filter(line -> !line.strip().startsWith(QUESTIONS_LABEL))
                .collect(java.util.stream.Collectors.joining("\n"))
                .strip();
        return kept.isEmpty() ? null : kept;
    }

    private static final String CONCEPTS_LABEL = "핵심 개념:";
    private static final String QUESTIONS_LABEL = "설명할 것:";
    private static final String OUT_OF_SCOPE_LABEL = "다루지 않을 것:";
    private static final String FACTS_LABEL = "실물:";
    private static final String DIAGRAM_LABEL = "그림:";
    private static final String LATER_LABEL = "뒤 편이 다룰 주제:";

    public TopicBrief {
        laterTopics = laterTopics == null ? List.of() : List.copyOf(laterTopics);
    }

    /**
     * 같은 분야 블록에서 이 편 뒤에 오는 주제들 — 심화편 전용.
     * 심화편은 질문 목록을 받지 않아 범위가 넓어지기 쉽고, 실제로 뒤 편 주제를 미리 썼다(2026-10-29).
     */
    public TopicBrief withLaterTopics(List<String> later) {
        return new TopicBrief(concepts, questions, outOfScope, facts, diagram, later,
                beginnerTopicBox, advancedTopicBox);
    }

    /** 형식을 갖춘 메모면 지시를, 아니면 {@code null}을 돌려준다. */
    public static TopicBrief parse(String memo) {
        if (memo == null || memo.isBlank()) {
            return null;
        }
        String concepts = null, questions = null, outOfScope = null, facts = null, diagram = null;
        for (String raw : memo.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith(CONCEPTS_LABEL)) {
                concepts = value(line, CONCEPTS_LABEL);
            } else if (line.startsWith(QUESTIONS_LABEL)) {
                questions = value(line, QUESTIONS_LABEL);
            } else if (line.startsWith(OUT_OF_SCOPE_LABEL)) {
                outOfScope = value(line, OUT_OF_SCOPE_LABEL);
            } else if (line.startsWith(FACTS_LABEL)) {
                facts = value(line, FACTS_LABEL);
            } else if (line.startsWith(DIAGRAM_LABEL)) {
                diagram = value(line, DIAGRAM_LABEL);
            }
        }
        if (concepts == null) {
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
        append(sb, CONCEPTS_LABEL, concepts);
        append(sb, QUESTIONS_LABEL, questions);
        append(sb, OUT_OF_SCOPE_LABEL, outOfScope);
        append(sb, FACTS_LABEL, facts);
        append(sb, DIAGRAM_LABEL, diagram);
        return sb.toString();
    }

    /**
     * 심화편에 싣는 줄. 질문 목록을 주면 입문편과 같은 글이 한 번 더 나오고,
     * 그림 지정을 주면 입문편이 그린 그림을 다시 그린다.
     */
    public String forAdvanced() {
        StringBuilder sb = new StringBuilder();
        append(sb, CONCEPTS_LABEL, concepts);
        append(sb, OUT_OF_SCOPE_LABEL, outOfScope);
        append(sb, FACTS_LABEL, facts);
        append(sb, LATER_LABEL, laterTopics.isEmpty() ? null : String.join(", ", laterTopics));
        return sb.toString();
    }

    private static void append(StringBuilder sb, String label, String value) {
        if (value != null) {
            sb.append(label).append(' ').append(value).append('\n');
        }
    }
}
