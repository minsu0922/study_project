package project.study.study_project.llm.support;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Spring 밖(배치 CLI·파일 읽기)에서 쓰는 공용 ObjectMapper.
 *
 * <p>여럿이 나눠 쓰므로 받은 쪽에서 {@code configure}로 설정을 바꾸지 않는다. 바꾸면 다른 클래스의 읽기·쓰기가 같이 바뀐다.
 */
public final class JsonMappers {

    /** 모르는 필드가 있으면 실패한다. 우리가 쓰고 우리가 읽는 파일용. */
    public static final ObjectMapper STRICT = new ObjectMapper();

    /** 모르는 필드는 건너뛴다. 형식이 앞서 바뀔 수 있는 파일(모델 응답·표본·지난 배치 결과)용. */
    public static final ObjectMapper LENIENT = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonMappers() {
    }
}
