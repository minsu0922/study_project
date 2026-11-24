package project.study.study_project.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.client.ReaderFinding;
import project.study.study_project.llm.client.ReaderFinding.Op;
import project.study.study_project.llm.domain.GeneratedDocumentDraft;
import project.study.study_project.llm.dto.DraftReaderFinding;
import project.study.study_project.llm.repository.GeneratedDocumentDraftRepository;
import project.study.study_project.llm.service.LlmDocumentService;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 독자 점검 지적의 수락·넘김 — 설계 2026-11-20 §4. 사실 검수 처리 테스트({@code LlmDocumentFlowIntegrationTest})와
 * 같은 이유로 실제 DB로 본다. 수락은 본문을 고치는 유일한 자동 경로라 엉뚱한 곳을 고치면 안 된다.
 */
@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class LlmDocumentReaderFindingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private LlmDocumentService llmDocumentService;
    @Autowired
    private GeneratedDocumentDraftRepository draftRepository;
    @Autowired
    private ObjectMapper objectMapper;

    private static final String BODY = "캐시는 **자주** 쓰는 값을 둔다. 캐시는 빠르다. 캐시는 빠르다고 했다.";

    private static ReaderFinding finding(Op op, String target, String text) {
        return new ReaderFinding(ReaderFinding.Kind.BLOCK, ReaderFinding.Type.UNCLEAR_SENTENCE,
                ReaderFinding.Weight.PAUSE, "왜 필요한가", "캐시는 빠르다.", "", "모르겠다",
                new ReaderFinding.Suggestion(op, target, text, "본문에서"));
    }

    private GeneratedDocumentDraft draftWith(String body, ReaderFinding... findings) throws Exception {
        GeneratedDocumentDraft draft = llmDocumentService.saveDraft(TestDomains.SYSTEM_DESIGN,
                new GeneratedDocumentItem("캐시 전략", "reader-test-" + UUID.randomUUID().toString().substring(0, 8),
                        validContent(body), List.of("cache")), "claude-opus-5");
        draft.recordReaderFindings(objectMapper.writeValueAsString(
                java.util.Arrays.stream(findings).map(DraftReaderFinding::of).toList()));
        draftRepository.flush();
        return draft;
    }

    @Test
    @DisplayName("바꾸기를 수락하면 그 문장만 바뀌고 처리 기록이 남는다 — 강조 표시가 있어도 자리를 찾는다")
    void acceptReplaceChangesOnlyTarget() throws Exception {
        GeneratedDocumentDraft draft = draftWith(BODY, finding(Op.REPLACE, "캐시는 자주 쓰는 값을 둔다.", "캐시는 자주 쓰는 값을 가까이 둔다."));

        var after = llmDocumentService.acceptReaderFinding(draft.getId(), 0);

        assertThat(after.contentMd()).contains("캐시는 자주 쓰는 값을 가까이 둔다. 캐시는 빠르다. 캐시는 빠르다고 했다.")
                .contains("## 무엇인가");
        assertThat(after.readerFindings().get(0).status()).isEqualTo("ACCEPTED");
        assertThat(after.readerFindings().get(0).resolution()).startsWith("수락: ").contains("배치 파일");
    }

    @Test
    @DisplayName("뒤에 넣기를 수락하면 같은 문단에 공백 하나를 두고 붙는다")
    void acceptInsertAfterAppendsInSameParagraph() throws Exception {
        GeneratedDocumentDraft draft = draftWith(BODY, finding(Op.INSERT_AFTER, "캐시는 빠르다.", "원본까지 가지 않기 때문이다."));

        var after = llmDocumentService.acceptReaderFinding(draft.getId(), 0);

        assertThat(after.contentMd()).contains("캐시는 빠르다. 원본까지 가지 않기 때문이다. 캐시는 빠르다고 했다.");
    }

    @Test
    @DisplayName("지우기를 수락하면 앞뒤 공백이 한 칸으로 줄어든다")
    void acceptDeleteCollapsesSpaces() throws Exception {
        GeneratedDocumentDraft draft = draftWith(BODY, finding(Op.DELETE, "캐시는 빠르다고 했다.", ""));

        var after = llmDocumentService.acceptReaderFinding(draft.getId(), 0);

        assertThat(after.contentMd()).contains("캐시는 **자주** 쓰는 값을 둔다. 캐시는 빠르다.\n")
                .doesNotContain("빠르다고 했다");
    }

    @Test
    @DisplayName("같은 문장을 고치는 두 제안 가운데 둘째는 문장이 바뀌어 거절된다")
    void secondAcceptOnChangedTargetIsRejected() throws Exception {
        GeneratedDocumentDraft draft = draftWith(BODY,
                finding(Op.REPLACE, "캐시는 빠르다.", "캐시는 원본보다 빠르다."),
                finding(Op.INSERT_AFTER, "캐시는 빠르다.", "덧붙임."));

        String first = llmDocumentService.acceptReaderFinding(draft.getId(), 0).contentMd();

        assertThatThrownBy(() -> llmDocumentService.acceptReaderFinding(draft.getId(), 1))
                .isInstanceOf(BusinessException.class).hasMessageContaining("제안한 문장이 본문에서 바뀌었습니다");
        assertThat(draftRepository.findById(draft.getId()).orElseThrow().getContentMd()).isEqualTo(first);
    }

    @Test
    @DisplayName("사람 확인이 필요한 지적은 수락할 수 없다")
    void needHumanCannotBeAccepted() throws Exception {
        GeneratedDocumentDraft draft = draftWith(BODY, finding(Op.NEED_HUMAN, "", ""));

        assertThatThrownBy(() -> llmDocumentService.acceptReaderFinding(draft.getId(), 0))
                .isInstanceOf(BusinessException.class).hasMessageContaining("넘김으로 처리하세요");
    }

    @Test
    @DisplayName("소제목에 걸린 제안은 수락하지 않고, 화면에도 수락할 수 없다고 알린다")
    void targetInHeadingIsRejected() throws Exception {
        GeneratedDocumentDraft draft = draftWith(BODY, finding(Op.REPLACE, "무엇인가", "정의"));

        assertThat(llmDocumentService.getDraft(draft.getId()).readerFindings().get(0).acceptable()).isFalse();
        assertThatThrownBy(() -> llmDocumentService.acceptReaderFinding(draft.getId(), 0))
                .isInstanceOf(BusinessException.class).hasMessageContaining("제안으로 고치지 않습니다");
    }

    @Test
    @DisplayName("제안을 넣으면 검증에 걸리는 경우(HTML 태그)는 수락하지 않는다")
    void acceptThatBreaksValidationIsRejected() throws Exception {
        GeneratedDocumentDraft draft = draftWith(BODY, finding(Op.REPLACE, "캐시는 빠르다.", "캐시는 <b>빠르다</b>."));

        assertThatThrownBy(() -> llmDocumentService.acceptReaderFinding(draft.getId(), 0))
                .isInstanceOf(BusinessException.class).hasMessageContaining("문서 검증에 걸립니다");
    }

    @Test
    @DisplayName("승인한 초안의 제안은 수락할 수 없다")
    void acceptOnApprovedDraftIsRejected() throws Exception {
        GeneratedDocumentDraft draft = draftWith(BODY, finding(Op.REPLACE, "캐시는 빠르다.", "캐시는 원본보다 빠르다."));
        llmDocumentService.approve(draft.getId());

        assertThatThrownBy(() -> llmDocumentService.acceptReaderFinding(draft.getId(), 0))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("넘기면 본문은 그대로이고 이유를 적으면 기록에 붙는다")
    void dismissKeepsContentAndRecordsNote() throws Exception {
        GeneratedDocumentDraft draft = draftWith(BODY,
                finding(Op.NEED_HUMAN, "", ""), finding(Op.REPLACE, "캐시는 빠르다.", "다른 문장."));
        String before = draft.getContentMd();

        llmDocumentService.dismissReaderFinding(draft.getId(), 0, null);
        var after = llmDocumentService.dismissReaderFinding(draft.getId(), 1, "원문이 맞다");

        assertThat(after.contentMd()).isEqualTo(before);
        assertThat(after.readerFindings()).extracting(f -> f.resolution()).containsExactly("넘김", "넘김: 원문이 맞다");
        assertThat(after.readerFindings()).extracting(f -> f.status()).containsExactly("DISMISSED", "DISMISSED");
    }

    @Test
    @DisplayName("제안이 없는 지적도 문단을 직접 고쳐 처리하고, 처리 뒤에는 고치기 칸을 주지 않는다")
    void editReplacesParagraphForNeedHuman() throws Exception {
        GeneratedDocumentDraft draft = draftWith(BODY, finding(Op.NEED_HUMAN, "", ""));
        assertThat(llmDocumentService.getDraft(draft.getId()).readerFindings().get(0).paragraph()).isEqualTo(BODY);

        var after = llmDocumentService.editReaderFinding(draft.getId(), 0, "캐시는 자주 쓰는 값을 가까이 둔다.");

        assertThat(after.contentMd()).contains("캐시는 자주 쓰는 값을 가까이 둔다.").doesNotContain(BODY).contains("## 무엇인가");
        assertThat(after.readerFindings().get(0).status()).isEqualTo("EDITED");
        assertThat(after.readerFindings().get(0).resolution()).startsWith("직접 수정: ").contains("배치 파일");
        assertThat(after.readerFindings().get(0).paragraph()).isNull();
    }

    @Test
    @DisplayName("빈 글·그대로인 글·검증에 걸리는 글은 직접 수정으로 받지 않고 본문을 지킨다")
    void editRejectsBadText() throws Exception {
        GeneratedDocumentDraft draft = draftWith(BODY, finding(Op.NEED_HUMAN, "", ""));
        String before = draft.getContentMd();

        assertThatThrownBy(() -> llmDocumentService.editReaderFinding(draft.getId(), 0, "  "))
                .isInstanceOf(BusinessException.class).hasMessageContaining("비어 있습니다");
        assertThatThrownBy(() -> llmDocumentService.editReaderFinding(draft.getId(), 0, BODY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("바뀐 곳이 없습니다");
        assertThatThrownBy(() -> llmDocumentService.editReaderFinding(draft.getId(), 0, "캐시는 <b>빠르다</b>."))
                .isInstanceOf(BusinessException.class).hasMessageContaining("문서 검증에 걸립니다");
        assertThat(draftRepository.findById(draft.getId()).orElseThrow().getContentMd()).isEqualTo(before);
    }

    @Test
    @DisplayName("독자 점검을 안 돌린 초안은 응답의 독자 점검이 null이다")
    void responseReaderFindingsNullWhenNotRun() {
        GeneratedDocumentDraft draft = llmDocumentService.saveDraft(TestDomains.SYSTEM_DESIGN,
                new GeneratedDocumentItem("캐시 전략", "reader-test-" + UUID.randomUUID().toString().substring(0, 8),
                        validContent(BODY), List.of("cache")), "claude-opus-5");

        assertThat(llmDocumentService.getDraft(draft.getId()).readerFindings()).isNull();
    }

    @Test
    @DisplayName("독자 점검 지적이 남아 있어도 승인된다")
    void approveIgnoresOpenReaderFindings() throws Exception {
        GeneratedDocumentDraft draft = draftWith(BODY, finding(Op.REPLACE, "캐시는 빠르다.", "다른 문장."));

        assertThat(llmDocumentService.approve(draft.getId()).id()).isNotNull();
    }

    @Test
    @DisplayName("단건 조회와 수락 API가 관리자 요청으로 동작하고, 사람 확인 지적의 수락은 400이다")
    void endpoints() throws Exception {
        GeneratedDocumentDraft draft = draftWith(BODY, finding(Op.NEED_HUMAN, "", ""));
        String token = "Bearer " + adminToken();

        mockMvc.perform(get("/api/admin/llm-documents/" + draft.getId()).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(draft.getId()))
                .andExpect(jsonPath("$.data.readerFindings[0].op").value("NEED_HUMAN"));
        mockMvc.perform(post("/api/admin/llm-documents/" + draft.getId() + "/reader-findings/0/accept")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/llm-documents/" + draft.getId() + "/reader-findings/0/dismiss")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"사람이 봤다\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readerFindings[0].status").value("DISMISSED"));
    }

    private String adminToken() {
        User admin = userRepository.save(User.builder()
                .username("rdr" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash(passwordEncoder.encode("admin-pw1"))
                .role(Role.ADMIN)
                .build());
        return jwtTokenProvider.createToken(admin.getId(), Role.ADMIN);
    }

    private static String validContent(String body) {
        return """
                # 캐시 전략

                > **글의 주제**
                >
                > 확인용 글의 주제.

                ## 핵심 요약
                - **요점** — 결론 한 문장.

                ## 바탕이 되는 개념
                상위 개념을 처음부터 설명한다.

                ## 무엇인가
                한 문장 정의.

                ## 왜 필요한가
                %s

                ## 용어 한눈에

                | 용어 | 한 줄 뜻 | 언제 쓰나 |
                |---|---|---|
                | 요점 | 한 줄 뜻. | 이럴 때. |

                ## 실무에서는 이렇게 쓴다
                - 이런 상황에서 이렇게 쓴다.

                ## 자주 하는 오해
                **"믿기 쉬운 문장이다"**
                그렇지 않다. 맞는 사실은 이것이다.

                ## 한 줄로 정리하면
                "한 줄."
                """.formatted(body);
    }
}
