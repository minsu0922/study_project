package project.study.study_project.global.response;

/**
 * 모든 컨트롤러 응답의 공통 envelope — 문서 04-response-format 기준.
 * <p>성공: {@code {success:true, data:..., error:null}},
 * 실패: {@code {success:false, data:null, error:{...}}}.
 */
public record ApiResponse<T>(boolean success, T data, ApiError error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    /** 본문 없는 성공 응답(예: 204에 준하는 200). */
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null);
    }

    public static ApiResponse<Void> fail(ApiError error) {
        return new ApiResponse<>(false, null, error);
    }
}
