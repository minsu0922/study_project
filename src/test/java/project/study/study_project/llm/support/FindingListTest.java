package project.study.study_project.llm.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.exception.BusinessException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 문제·문서 초안의 검수 지적 처리가 같이 쓰는 단계(REVIEW 2026-11-01 순위 6). */
class FindingListTest {

    record Note(String text, String resolution) implements FindingList.Resolvable<Note> {
        @Override
        public Note resolvedWith(String resolution) {
            return new Note(text, resolution);
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("없는 번호와 이미 처리한 지적은 화면에 보일 이유로 막는다")
    void requireOpenRejectsMissingAndResolved() {
        List<Note> notes = List.of(new Note("a", null), new Note("b", "고치지 않음"));

        assertThat(FindingList.requireOpen(notes, 0, Note::resolution).text()).isEqualTo("a");
        assertThatThrownBy(() -> FindingList.requireOpen(notes, 2, Note::resolution))
                .isInstanceOf(BusinessException.class).hasMessageContaining("그런 지적이 없습니다");
        assertThatThrownBy(() -> FindingList.requireOpen(notes, 1, Note::resolution))
                .isInstanceOf(BusinessException.class).hasMessageContaining("이미 처리한 지적입니다");
        assertThatThrownBy(() -> FindingList.requireOpen(null, 0, Note::resolution))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("처리는 그 번호만 기록을 단 새 목록을 돌려주고 원래 목록은 그대로 둔다")
    void resolveReturnsCopy() {
        List<Note> notes = List.of(new Note("a", null), new Note("b", null));

        List<Note> updated = FindingList.resolve(notes, 1, "직접 수정");

        assertThat(updated).extracting(Note::resolution).containsExactly(null, "직접 수정");
        assertThat(notes.get(1).resolution()).isNull();
    }

    @Test
    @DisplayName("저장된 JSON을 읽고 쓴다. 검수를 안 돌린 초안(null)은 null, 깨진 JSON은 이유로 막는다")
    void readAndWrite() {
        String json = FindingList.write(mapper, List.of(new Note("a", null)));

        assertThat(FindingList.read(mapper, json, new TypeReference<List<Note>>() {
        }, "지적")).containsExactly(new Note("a", null));
        assertThat(FindingList.read(mapper, null, new TypeReference<List<Note>>() {
        }, "지적")).isNull();
        assertThatThrownBy(() -> FindingList.read(mapper, "{깨짐", new TypeReference<List<Note>>() {
        }, "사실 검수 지적")).isInstanceOf(BusinessException.class).hasMessageContaining("사실 검수 지적을 읽지 못했습니다");
    }
}
