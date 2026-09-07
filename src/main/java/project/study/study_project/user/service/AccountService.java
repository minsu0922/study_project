package project.study.study_project.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.dailyquiz.repository.DailyQuizRepository;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.quiz.repository.SubmissionRepository;
import project.study.study_project.report.repository.ProblemReportRepository;
import project.study.study_project.review.repository.ReviewItemRepository;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.dto.ChangePasswordRequest;
import project.study.study_project.user.dto.WithdrawRequest;
import project.study.study_project.user.repository.UserRepository;

/**
 * 내 계정 관리 — 비밀번호 변경과 탈퇴 (2026-09-08).
 *
 * <h2>왜 이제야 생겼나</h2>
 *
 * <p>기능 점검(docs/REVIEW_2026-09-07-features 4장)에서 3순위로 나왔다. 한 번 정한
 * 비밀번호를 <b>바꿀 수도, 계정을 지울 수도 없었다.</b>
 *
 * <h2>비밀번호 <b>찾기</b>는 여기 없다</h2>
 *
 * <p>이메일을 안 받으므로 재설정 메일을 보낼 곳이 없다 — 미룬 것이 아니라 지금 구조에서
 * <b>불가능</b>하다. 소셜 로그인이 붙으면 그 계정 자체가 복구 경로가 되므로 그때 함께
 * 정한다. 화면은 이 사실을 숨기지 않고 적는다.
 *
 * <h2>둘 다 지금 비밀번호를 요구한다</h2>
 *
 * <p>토큰이 있다는 것은 "이 브라우저가 로그인했다"이지 "지금 앉아 있는 사람이 본인이다"가
 * 아니다. 잠깐 자리를 비운 사이 남이 만지면, 확인이 없을 때 비밀번호가 바뀌어 계정을
 * 통째로 빼앗긴다. 탈퇴는 더하다 — 되돌릴 수 없고 되살려 줄 방법도 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private final SubmissionRepository submissionRepository;
    private final ReviewItemRepository reviewItemRepository;
    private final DailyQuizRepository dailyQuizRepository;
    private final ProblemReportRepository problemReportRepository;

    /**
     * 비밀번호 변경.
     *
     * <p>실패는 로그인과 <b>같은 오류</b>로 돌려준다(AUTH_002). "지금 비밀번호가 틀렸다"와
     * "그런 계정이 없다"를 구별해 주면, 그 차이만으로 남의 계정 존재를 확인할 수 있다.
     */
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_002));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.AUTH_002);
        }
        user.changePassword(passwordEncoder.encode(request.newPassword()));
        log.info("비밀번호 변경: userId={}", userId);   // 값은 절대 남기지 않는다
    }

    /**
     * 탈퇴 — 계정과 그 계정의 학습 기록을 <b>실제로 지운다</b>.
     *
     * <h2>왜 소프트 삭제가 아닌가</h2>
     *
     * <p>플래그만 세우면 "지웠다"고 말할 수 없다. 지워 달라는 요청에 지운 척으로 답하는 것은
     * 거짓말이고, 이 앱이 보관해서 얻을 것도 없다(광고도, 정산도, 법정 보존 의무도 없다).
     *
     * <h2>지우는 순서가 곧 제약 조건이다</h2>
     *
     * <p>자식부터 지운다. 순서가 틀리면 외래 키 제약에 걸려 트랜잭션이 통째로 굴러떨어지는데,
     * 증상은 "탈퇴 버튼을 눌렀는데 아무 일도 안 일어남"이라 원인을 짐작하기 어렵다.
     *
     * <h2>왜 JPA의 연쇄(cascade)에 맡기지 않았나 — 실제로 겪은 일</h2>
     *
     * <p>처음에는 데일리 퀴즈만 엔티티로 읽어 {@code deleteAll}에 넘겼다. 항목이
     * {@code cascade = ALL}로 매달려 있으니 손자까지 따라올 것이라 봤고, 통합 테스트도
     * 통과했다. 그런데 <b>브라우저로 눌러 보니 500</b>이 났다.
     *
     * <p>원인은 <b>실행 순서</b>다. 벌크 삭제({@code @Modifying})는 그 자리에서 SQL을
     * 날리지만 엔티티 삭제는 <b>플러시 때</b> 나간다. 그리고 그 순서는 하이버네이트가
     * 정하는데, {@code User}와 {@code DailyQuiz} 사이에 매핑된 연관이 없으므로
     * <b>둘의 앞뒤를 보장하지 않는다</b> — 사용자를 먼저 지우려 들면 외래 키에 걸린다.
     *
     * <p>그래서 전부 벌크로, <b>순서를 손으로</b> 적는다. 연쇄에 기대는 대신 지우는 차례가
     * 코드에 그대로 보이는 편이 낫다 — 나중에 자식이 하나 늘어도 어디에 끼워 넣을지가
     * 눈에 보인다.
     *
     * <p>마지막에 {@code flush}를 부르는 이유: 이 메서드가 성공으로 돌아간 뒤에 제약
     * 위반이 터지면, 컨트롤러는 이미 200을 내보낸 뒤다. 여기서 확정해야 실패가 실패로
     * 보인다.
     *
     * <h2>테스트가 이 사고를 못 잡았다</h2>
     *
     * <p>{@code @Transactional} 테스트는 롤백되므로 플러시가 일어나지 않고, 삭제한 엔티티
     * 조회는 <b>영속성 컨텍스트에서</b> 빈 값을 돌려준다 — DB까지 가지 않으니 제약 위반이
     * 드러날 자리가 없었다. 테스트에 플러시를 넣어 그 구멍을 막았다.
     *
     * <h2>남은 리프레시 토큰</h2>
     *
     * <p>Redis에 남지만 무해하다. 재발급은 토큰을 쓴 뒤 <b>사용자를 찾는데</b>, 그 행이
     * 없으니 실패한다. 토큰 저장소를 뒤져 지우는 코드를 더하는 것보다, 이미 있는 안전장치가
     * 막아 준다는 사실을 적어 두는 편이 낫다.
     */
    @Transactional
    public void withdraw(Long userId, WithdrawRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_002));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.AUTH_002);
        }

        dailyQuizRepository.deleteItemsByUserId(userId);   // 손자 먼저
        dailyQuizRepository.deleteAllByUserId(userId);
        reviewItemRepository.deleteAllByUserId(userId);
        submissionRepository.deleteAllByUserId(userId);
        problemReportRepository.deleteAllByUserId(userId);
        userRepository.delete(user);
        userRepository.flush();   // 아래 주석 참고 — 여기서 SQL을 확정한다

        log.info("탈퇴: userId={}", userId);
    }
}
