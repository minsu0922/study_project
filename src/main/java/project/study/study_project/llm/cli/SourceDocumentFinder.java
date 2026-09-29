package project.study.study_project.llm.cli;

import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.ClaudeProblemGenerator;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.client.SourceDocument;
import project.study.study_project.llm.dto.GeneratedDocumentFile;
import project.study.study_project.llm.support.DifficultyMaterialRule;
import project.study.study_project.llm.support.DocumentEditionRule;
import project.study.study_project.llm.support.TypeMaterialRule;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * 문제의 근거가 될 개념 문서를 찾고, 그 문서에 이 난이도의 재료가 있는지 본다.
 *
 * <p>{@link DraftGeneratorCli}에서 역할별로 떼어 냈다(2026-09-29). 동작은 그대로다.
 */
final class SourceDocumentFinder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SourceDocumentFinder() {
    }

    /**
     * 주기가 계산한 분야와 근거 문서의 분야가 어긋나면 <b>문서 쪽으로 맞춘다</b>(2단계).
     *
     * <p><b>왜 이런 일이 생기나.</b> 문서는 수동 실행으로도 만들 수 있고(주제·날짜를 직접 지정),
     * 주기를 도입하기 전에 만들어진 문서도 있다. 실제로 2026-08-11에 손으로 만든 문서
     * (캐시 전략, SYSTEM_DESIGN)가 OS 주기의 근거로 잡히는 상황이 있었다.
     *
     * <p><b>그냥 두면 무슨 일이 벌어지나.</b> "운영체제 문제를 내라"와 "이 캐시 문서 안에서만
     * 내라"라는 <b>모순된 지시</b>가 한 프롬프트에 함께 실린다. 모델은 오류를 내지 않는다 —
     * 둘 중 하나를 무시하거나 어정쩡하게 섞은 문제를 만들어 낸다. 검수자는 "왜 OS 칸에
     * 캐시 문제가 있지?"를 한참 뒤에야 알아차린다.
     *
     * <p><b>왜 문서가 이기나.</b> 근거 문서가 곧 그 주기의 주제다. 분야는 그 주제를 분류한
     * 이름표일 뿐이라, 이름표를 지키자고 실제 내용과 어긋나게 둘 이유가 없다.
     * (사용자가 {@code --domain}으로 직접 지정한 경우는 호출부에서 이 조정을 건너뛴다.)
     *
     * @param planDomain     주기가 계산한 분야
     * @param documentDomain 근거 문서에 기록된 분야. {@code null}이면 조정하지 않는다
     *                       (옛 형식 파일 방어 — 알 수 없는 값 때문에 멀쩡한 분야를 버리면 안 된다)
     */
    static DomainCode alignDomainWithDocument(DomainCode planDomain, DomainCode documentDomain) {
        return documentDomain != null ? documentDomain : planDomain;
    }

    /* ── 근거 문서 찾기(2단계) ───────────────────────────────── */

    /**
     * 근거 문서를 읽는다 — 없거나 쓸 수 없으면 {@code null}(폴백).
     *
     * <p><b>왜 {@code Plan}이 아니라 날짜를 받나</b>(2026-08-29). 원래는 {@code plan.documentDate()}를
     * 안에서 꺼내 썼다. 그러면 "어느 문서를 읽을지"가 주기 계산에 묶여, 문서를 지목하려면
     * 그 문서의 주기에 속하는 날짜를 {@code --date}로 넘기는 수밖에 없었다. 그런데 그 날짜는
     * 주기당 셋뿐인 데다 결과 파일명이기도 해서, 한 번 쓴 날짜는 다시 못 쓴다
     * (같은 이름이면 멱등성 검사가 건너뛰고, 흡수 이력도 파일명으로 남는다).
     * 실제로 보안 문서 두 편이 그 셋을 다 소진해 <b>남은 난이도를 채울 방법이 없어졌다</b>.
     *
     * <p>날짜 하나만 받게 바꾸니 두 축이 갈라진다 — {@code --date}는 "결과를 어디에 쓸지",
     * {@code --document-date}는 "무엇을 근거로 할지". 예약 실행은 여전히 주기가 둘 다 정하므로
     * 평소 경로는 그대로다.
     *
     * <p><b>클라우드에 DB가 없는데 어떻게 문서를 읽나.</b> 문서는 이미 저장소에 커밋돼 있다
     * ({@code generated/documents/YYYY-MM-DD.json}). Actions는 저장소를 통째로 내려받고 시작하므로
     * <b>파일을 그냥 열면 된다</b>. 날짜만으로 어느 파일인지 정해지니(주기 0일차) 어긋날 일도 없다.
     * 문서 흡수 때 겪은 "클라우드는 DB를 못 본다" 문제가 여기서는 저절로 풀린다.
     *
     * <p><b>거절된 문서는 쓰지 않는다.</b> 검수자가 "이건 아니다"라고 판단한 문서로 사흘간 문제를
     * 만들면 그 사흘이 통째로 낭비다. 거절 여부는 로컬 DB에만 있으므로
     * {@code _existing-documents.json}의 {@code rejectedSlugs}로 실어 보낸다.
     *
     * <p><b>아직 검수 안 한 문서는 그냥 쓴다.</b> 미승인은 부정 신호가 아니라 "아직 안 봤다"일 뿐인데,
     * 승인을 며칠 미뤘다고 그 주기를 날리면 사람의 검수 속도에 배치가 인질로 잡힌다.
     */
    // package-private인 이유는 resolveProblemType·hasMaterialFor와 같다 — 테스트가 부른다.
    // 여기서 보려는 것은 규칙 자체(그건 TypeMaterialRuleTest의 몫)가 아니라 <이 자리에서 규칙을
    // 실제로 부르는가>다. 인자를 하나 빠뜨려도 컴파일은 되므로, 그 실수는 테스트로만 잡힌다.
    static ResolvedSource findSourceDocument(Path outDir, LocalDate documentDate,
                                             Difficulty difficulty, ProblemType type) {
        if (difficulty == null) {
            return null; // 문서일에는 근거 문서를 찾을 일이 없다(방어)
        }
        Path file = outDir.resolve(DraftGeneratorCli.DOCUMENT_SUBDIR).resolve(documentDate + ".json");
        if (!Files.exists(file)) {
            System.out.println("근거 문서 없음, 폴백으로 생성합니다: " + file);
            return null;
        }
        try {
            GeneratedDocumentFile parsed = MAPPER.readValue(file.toFile(), GeneratedDocumentFile.class);
            GeneratedDocumentItem doc = editionFor(parsed, difficulty);
            if (doc == null || doc.contentMd() == null || doc.contentMd().isBlank()) {
                System.out.println("근거 문서 본문이 비어 폴백으로 생성합니다: " + file);
                return null;
            }
            // 중급은 심화편에 입문편의 중급 절 둘이 붙은 본문을 읽는다(2026-09-17,
            // DocumentEditionRule.bodyFor). 아래 검사들이 전부 <이 본문>을 봐야 한다 —
            // 편의 본문만 보고 통과·탈락을 정하면, 정작 모델이 받는 글과 다른 것을 잰 셈이 된다.
            String body = DocumentEditionRule.bodyFor(parsed, difficulty);
            if (DraftGeneratorCli.readExistingDocuments(outDir).rejectedSlugs().contains(doc.slug())) {
                System.out.println("근거 문서가 검수에서 거절돼 폴백으로 생성합니다: " + doc.slug());
                return null;
            }
            if (!hasMaterialFor(body, difficulty)) {
                System.out.printf("근거 문서에 %s 재료가 없어 폴백으로 생성합니다: %s (찾은 절: %s 중 하나도 없음)%n",
                        difficulty, doc.slug(), ClaudeProblemGenerator.SOURCE_SECTIONS.get(difficulty));
                return null;
            }
            // 난이도 재료와 <같은 자리에서> 유형 재료도 본다(2026-09-01). 난이도는 "캘 절이
            // 있는가", 유형은 "그 절에 짝지을 것이 넷 있는가"를 묻는다 — 둘 다 통과해야 쓴다.
            //
            // 여기서 null을 돌려주면 지목 실행은 위쪽 documentPinned 검사에 걸려 <요금 0으로 실패>하고,
            // 예약 실행은 폴백으로 간다. 새 실패 경로를 만들지 않고 기존 갈래에 얹은 이유가 이것이다.
            String missing = TypeMaterialRule.missingMaterialOf(body, type);
            if (missing != null) {
                System.out.printf("근거 문서로 %s를 만들 수 없어 폴백으로 생성합니다: %s (%s)%n",
                        type, doc.slug(), missing);
                return null;
            }
            // slug·제목은 심화편 것을 그대로 쓴다. 붙인 부분은 <같은 주제 같은 제목>의 입문편이고,
            // 초안이 "어느 문서로 만들었나"를 가리킬 때 답은 여전히 그 문서 한 편이다.
            return new ResolvedSource(parsed.domain(),
                    new SourceDocument(doc.slug(), doc.title(), body));
        } catch (Exception e) {
            // 문서를 못 읽는 것이 그날 문제 생성을 막을 이유는 없다 — 근거 없이라도 만든다
            System.out.println("근거 문서를 읽지 못해 폴백으로 생성합니다: " + e.getMessage());
            return null;
        }
    }

    /**
     * 오늘 난이도가 <b>어느 편을 근거로 삼는지</b> — 초급은 입문편, 중급·고급은 심화편.
     *
     * <p><b>왜 이 매핑인가.</b> {@code ClaudeProblemGenerator.SOURCE_SECTIONS}가 난이도별로
     * 지목하는 절이 두 편의 분할선과 그대로 일치한다 — 초급({@code ## 바탕이 되는 개념},
     * {@code ## 무엇인가})은 입문편에, 중급({@code ## 실제로는 어디에서 만나는가})과
     * 고급({@code ## 언제 깨지는가}, {@code ## 면접에서 이렇게 물어본다})은 심화편에 있다.
     *
     * <p><b>2026-09-14에 중급을 심화편으로 옮겼다.</b> 전에는 심화편이 고급 3문제만 떠받쳐
     * 문제당 재료가 3,465자였다(입문편은 955자로 12문제). 남는 지면을 모델이 이론으로 채우면서
     * 글이 계속 어려워졌고, 중급을 붙여 <b>바닥</b>을 만들었다 — 같은 문서로 중급도 내야 하면
     * 너무 어려워지는 순간 중급을 만들 수 없다. 사정은
     * {@code ClaudeDocumentGenerator.ADVANCED_REQUIRED_SECTIONS} 주석에 적어 뒀다.
     *
     * <p>덤으로 중급 프롬프트의 <b>형태 배분 규칙</b>(다섯 형태, SITUATION 최대 2개)이 심화편
     * 재료를 캐게 된다. 고급에만 맡겨 두면 상황형으로 쏠리던 자리다.
     *
     * <p><b>심화편이 없으면 입문편으로 돌아간다.</b> 2026-09-03 이전에 만든 파일 15개에는
     * 심화편 칸이 아예 없고, 심화편 생성만 실패한 날도 있을 수 있다. 여기서 {@code null}을
     * 돌려주면 그날 고급이 근거 없는 폴백이 되는데, 옛 문서에는 {@code ## 언제 깨지는가}가
     * 실제로 들어 있으므로 <b>입문편(옛 단일 문서)을 주는 편이 낫다</b>.
     * 재료가 정말 없으면 바로 다음 검사({@link #hasMaterialFor})가 걸러 준다 —
     * 여기서 미리 판단하면 그 검사와 판정이 둘로 갈린다.
     *
     * <p>두 편을 이어 붙여 넘기는 안은 버렸다. 고급 프롬프트에 입문편이 함께 실리면 모델이
     * 거기서도 캐서 <b>고급인데 초급 재료로 낸 문제</b>가 섞인다. 근거 인용 대조도 넘긴 본문
     * 기준이라, 한 편만 주면 그대로 맞아떨어진다.
     */
    static GeneratedDocumentItem editionFor(GeneratedDocumentFile parsed, Difficulty difficulty) {
        // 판정은 DocumentEditionRule 한 곳에만 둔다(2026-09-14). 여기에 조건을 직접 적으면
        // 관리 화면의 배치 현황과 갈라지는데, 그 어긋남이 실제로 있었다 —
        // 화면은 입문편 slug를 찍고 배치는 심화편으로 돌았다. hasMaterialFor를
        // DifficultyMaterialRule에 맡긴 것과 같은 처방이다.
        return DocumentEditionRule.pick(parsed, difficulty);
    }

    /**
     * 이 문서에 <b>오늘 난이도가 캘 재료가 남아 있는지</b> — 없으면 문서를 쓰지 않는다(폴백).
     *
     * <p><b>왜 필요한가.</b> 지금까지 근거 문서를 거르는 기준은 "파일이 있나 / 본문이 비었나 /
     * 거절됐나" 셋뿐이었다. 셋 다 통과하지만 <b>오늘 쓸 절만 없는</b> 문서가 실제로 나왔다 —
     * 2026-08-15 주기의 문서에 중급이 지목하는 두 절이 둘 다 없었다(절 이름을 문서 생성 뒤에
     * 바꿨기 때문, 커밋 2a5538c). 승인된 문서는 재검증되지 않아 아무도 몰랐다.
     *
     * <p><b>없으면 왜 나쁜가.</b> 프롬프트가 없는 절을 지목하면 모델은 멈추지 않고
     * <b>알아서 다른 절을 캔다</b>. 하필 {@code ## 언제 깨지는가}를 캐면 그날 중급이 고급처럼
     * 나오고, 다음 날 고급은 이미 쓴 재료 앞에서 빈손이 된다(8/14에 겪은 경로).
     * 실패가 아니라 <b>조용한 품질 저하</b>라 며칠 뒤 사람이 눈으로 알아차릴 때까지 간다.
     *
     * <p><b>왜 job을 실패시키지 않고 폴백인가.</b> 문서가 못 쓸 물건인 것은 <b>어제까지의
     * 사고</b>지 오늘 배치의 잘못이 아니다. 여기서 예외를 던지면 그날 문제가 0개가 되는데,
     * 폴백으로 가면 모델 지식으로라도 그날 치는 나온다 — "근거 문서가 없는 날"과 똑같은 상황으로
     * 취급하면 되고, 그 경로는 호출부에 이미 있다(난이도까지 옛 24칸 순환으로 되돌린다).
     * 대신 사유를 또렷이 찍어 로그만 봐도 원인을 알 수 있게 한다.
     *
     * <p><b>왜 "하나라도 있으면 통과"인가(전부가 아니라).</b> 중급 지시는 절 이름 외에
     * "본문 문장 안에서 판단한 대목"까지 재료로 인정한다. 두 절을 모두 요구하면 재료가 멀쩡히
     * 있는 문서까지 폴백으로 버려진다. 여기서 막으려는 것은 "조금 부족한 문서"가 아니라
     * <b>캘 곳이 하나도 없는 문서</b>다 — 문턱을 낮게 두어야 오탐이 안 난다.
     * (오탐으로 폴백이 잦아지면 2단계 구조 자체가 헛돈다.)
     *
     * <p><b>2026-09-05에 판정을 {@link DifficultyMaterialRule}로 옮겼다.</b> 여기 있던 규칙이
     * <b>배치에만</b> 있어서, 관리자 화면의 "문서로 문제 만들기"는 입문편에 고급을 걸어도 그대로
     * 통과했다. {@link TypeMaterialRule}이 유형에 대해 이미 같은 이유로 뽑혀 나온 것과 같다.
     * 이 메서드는 <b>이름과 폴백 동작</b>을 지키려고 남긴 얇은 껍데기다 — 부르는 자리의 주석과
     * 테스트가 이 이름에 걸려 있고, 위임만 하므로 두 벌이 될 여지는 없다.
     *
     * @param contentMd  문서 본문 마크다운
     * @param difficulty 오늘 만들 난이도. {@code null}이면 검사할 것이 없으므로 통과
     */
    static boolean hasMaterialFor(String contentMd, Difficulty difficulty) {
        return DifficultyMaterialRule.hasMaterialFor(contentMd, difficulty);
    }

    /**
     * 찾아낸 근거 문서와 <b>그 문서가 속한 분야</b>.
     *
     * <p>분야를 따로 들고 다니는 이유: 주기가 계산한 분야와 실제 문서의 분야가 어긋날 수 있고,
     * 그때는 문서 쪽으로 맞춰야 한다(위 호출부 주석). {@link SourceDocument}에 분야를 넣지 않은 것은
     * 그 record가 <b>프롬프트에 실릴 내용</b>만 담는 그릇이기 때문이다 — 분야는 프롬프트의
     * 다른 자리(조건 줄)에 이미 들어가므로 문서 블록에 또 넣으면 중복이다.
     */
    record ResolvedSource(DomainCode domain, SourceDocument document) {
    }
}
