package project.study.study_project.admin.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import project.study.study_project.llm.dto.GeneratedPending;
import project.study.study_project.llm.dto.GeneratedPublishResult;
import project.study.study_project.llm.service.GeneratedPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 배치로 보내기 API — 기능을 끄거나 git이 막혀도 화면이 이유를 받는지 본다. */
class AdminGeneratedControllerTest {

    @Test
    @DisplayName("기능이 꺼져 빈이 없으면 조회·보내기 모두 쓸 수 없다고 답한다")
    void disabled() {
        AdminGeneratedController controller = new AdminGeneratedController(provider(null));

        assertThat(controller.pending().data().available()).isFalse();
        assertThat(controller.pending().data().unavailableReason()).isEqualTo(AdminGeneratedController.DISABLED);
        assertThat(controller.publish().data().status()).isEqualTo(GeneratedPublishResult.Status.UNAVAILABLE);
    }

    @Test
    @DisplayName("조회 중 git이 실패해도 오류 대신 이유를 담아 돌려준다")
    void pendingFailureBecomesReason() {
        GeneratedPublisher publisher = mock(GeneratedPublisher.class);
        when(publisher.pending()).thenThrow(new IllegalStateException("git status 실패"));

        GeneratedPending pending = new AdminGeneratedController(provider(publisher)).pending().data();

        assertThat(pending.available()).isFalse();
        assertThat(pending.unavailableReason()).contains("git status 실패");
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<GeneratedPublisher> provider(GeneratedPublisher publisher) {
        ObjectProvider<GeneratedPublisher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(publisher);
        return provider;
    }
}
