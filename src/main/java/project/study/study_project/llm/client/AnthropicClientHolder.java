package project.study.study_project.llm.client;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;

/**
 * Anthropic SDK 클라이언트를 한 번만 만들어 공유한다 — 문제 생성기와 문서 생성기가 함께 쓴다.
 *
 * <p><b>왜 클래스로 뺐나.</b> 원래 이 지연 생성 로직은 {@link ClaudeProblemGenerator} 안에 있었는데,
 * 문서 생성기가 생기면서 같은 코드가 두 벌이 될 상황이었다. 복사하면 "키가 없을 때 LLM_004로 안내"
 * 같은 규칙이 한쪽만 바뀌어 어긋난다. 게다가 생성기마다 클라이언트를 따로 만들면
 * <b>커넥션 풀도 따로</b> 생긴다 — SDK 클라이언트는 재사용이 원칙인 무거운 객체다.
 *
 * <p><b>왜 지연 생성인가.</b> {@code fromEnv()}는 ANTHROPIC_API_KEY가 없으면 예외를 던진다.
 * 앱 부팅 시점에 만들면 키 없는 로컬 환경에서 <b>앱 자체가 안 뜬다</b>. 키가 없어도 앱은 뜨고
 * 생성 기능만 LLM_004로 안내하는 게 맞다 — 생성은 부가 기능이지 앱의 전제가 아니다.
 *
 * <p>{@code volatile} + {@code synchronized}의 이중 검사는 "여러 스레드가 동시에 첫 호출을 해도
 * 클라이언트가 한 번만 만들어지게" 하는 최소한의 보호다. 하루 몇 번 호출되는 기능이라
 * 더 정교한 동시성 제어는 과하다.
 */
final class AnthropicClientHolder {

    private static volatile AnthropicClient client;

    private AnthropicClientHolder() {
    }

    /**
     * 클라이언트를 얻는다. 키가 설정돼 있지 않으면 {@link ErrorCode#LLM_004}(503)로 기능 비활성을 알린다.
     *
     * @throws BusinessException ANTHROPIC_API_KEY 환경변수가 없을 때
     */
    static AnthropicClient get() {
        AnthropicClient c = client;
        if (c == null) {
            synchronized (AnthropicClientHolder.class) {
                if (client == null) {
                    if (System.getenv("ANTHROPIC_API_KEY") == null) {
                        throw new BusinessException(ErrorCode.LLM_004);
                    }
                    client = AnthropicOkHttpClient.fromEnv();
                }
                c = client;
            }
        }
        return c;
    }
}
