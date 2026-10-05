package project.study.study_project.auth.dto;

/**
 * 아이디나 닉네임을 쓸 수 있는지에 대한 답.
 *
 * @param reason 쓸 수 없으면 그 이유 — 가입이 같은 값을 거절할 때의 문구와 같다. 쓸 수 있으면 {@code null}
 */
public record AvailabilityResponse(boolean available, String reason) {

    public static AvailabilityResponse ok() {
        return new AvailabilityResponse(true, null);
    }

    public static AvailabilityResponse no(String reason) {
        return new AvailabilityResponse(false, reason);
    }
}
