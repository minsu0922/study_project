/* 관리 화면 공통 — 네비게이션·가드·배지·자잘한 도우미.
 *
 * [왜 /admin/js/ 안에 두나]
 * 이 파일은 관리 API 경로를 그대로 담고 있다. /js/ 에 두면 관리자 쿠키의 Path=/admin에
 * 걸리지 않아 <b>누구나 내려받을 수 있고</b>, 그러면 화면을 감춘 의미가 반쯤 사라진다.
 * 감추려는 것은 HTML만이 아니라 "관리 기능이 어떻게 생겼는지" 전체다.
 *
 * [탭에서 페이지로 — 무엇이 달라졌나]
 * 예전 admin.html은 1,417줄짜리 한 파일에 탭 다섯 개가 들어 있었고, 탭마다 데이터를
 * 언제 부를지 관리하는 장치(loadedTabs·refreshTabs)가 필요했다. 페이지를 나눈 지금은
 * <b>그 장치가 통째로 사라진다</b> — 페이지 하나가 제 데이터만 열 때 부르면 되기 때문이다.
 * 요청 수도 줄어든다(예전엔 진입 시 안 보이는 탭까지 신경 써야 했다).
 *
 * 대신 잃는 것: 탭을 오갈 때 유지되던 폼 입력이 페이지 이동으로 날아간다.
 * 페이지마다 하는 일이 하나뿐이라 오갈 일 자체가 줄어든다고 보고 감수한다.
 */

/**
 * 관리 화면 목록 — 네비게이션과 "여기가 어디인지" 표시에 함께 쓴다.
 *
 * <h2>순서를 일의 단계로 바꿨다 (2026-08-29)</h2>
 *
 * <p>예전 메뉴는 <b>무엇에 대한 것인가</b>(문제/문서/주제)로 나뉘어 있었는데, 실제 일은
 * <b>어느 단계인가</b>로 흐른다: 현황을 보고 → 만들고 → 검수하고 → 정식 콘텐츠를 손본다.
 * 그 어긋남이 불편의 뿌리였다.
 *
 * <p>가장 뚜렷했던 것이 옛 "AI 검수"다. 이름은 검수인데 실제로는 셋을 했다 —
 * 생성(요금)·문제 검수·문서 검수. 생성을 떼어내니 검수 화면은 열자마자 대기 목록이 되고
 * (다 처리하면 빈 화면 = "오늘 할 일 없음"), <b>요금이 나가는 버튼이 생성 한 곳에 모인다.</b>
 *
 * <p>"주제 범위"는 별도 메뉴에서 생성 안으로 들어갔다. 주제를 정하는 것은 생성의 입력이지
 * 별개 작업이 아닌데, 메뉴 맨 끝에 있어 실제 순서(주제 → 생성)와 반대로 놓여 있었다.
 * 옛 주소 {@code /admin/topics.html}은 생성으로 넘겨 준다(북마크·문서 링크 보호).
 */
const ADMIN_PAGES = [
  ["dashboard", "현황", "/admin/index.html"],
  ["llm", "검수", "/admin/llm.html"],
  ["generate", "생성", "/admin/generate.html"],
  ["problems", "문제", "/admin/problems.html"],
  ["documents", "문서", "/admin/documents.html"],
];

/**
 * 관리 페이지 공통 초기화 — 가드 → 네비 → 배지 순서로 한 번에 처리한다.
 *
 * <p>가드가 먼저인 이유: 관리자가 아니면 네비를 그릴 이유가 없다. 그리고 이 가드는
 * <b>장식</b>이다 — 진짜 방어는 서버 두 겹이다(정적 파일은 AdminGateFilter의 쿠키 검사,
 * API는 SecurityConfig의 hasRole). 여기서 막는 것은 "로그아웃한 채 뒤로가기로 돌아온"
 * 상황에서 빈 화면 대신 안내를 보여 주기 위해서다.
 *
 * @param active 지금 페이지의 키(ADMIN_PAGES의 첫 칸)
 * @returns 관리자면 true — 호출부는 이 값이 false면 데이터 적재를 건너뛴다
 */
function initAdminPage(active) {
  renderConsoleNav();   // 콘솔 전용 상단 바 — 여기서는 학습 메뉴를 보여 주지 않는다

  if (!isAdmin()) {
    document.getElementById("guard").innerHTML =
      `<div class="alert error">관리자 전용 페이지입니다. 관리자 계정으로 <a href="/login.html">로그인</a>해 주세요.</div>`;
    return false;
  }
  document.getElementById("adminUi").hidden = false;
  renderAdminNav(active);
  refreshAdminBadges();
  return true;
}

/**
 * 콘솔 상단 바 — <b>학습 메뉴가 없다</b>는 것이 사용자 화면과의 차이다.
 *
 * <p>관리 콘솔은 별도 영역이다. 여기에 홈·자유 퀴즈·복습이 함께 떠 있으면 "지금 어느 쪽에
 * 있는지"가 흐려지고, 검수하다가 잘못 눌러 나가기도 쉽다. 대신 <b>나가는 문 하나</b>를
 * 왼쪽에 크게 둔다 — 관리자도 학습자라 오갈 일이 있고, 막을 이유는 없다.
 *
 * <p>로그인 표시와 로그아웃은 사용자 화면과 <b>같은 함수</b>를 쓴다({@code authAreaHtml},
 * {@code wireLogout}). 복사해 두면 서버 토큰 폐기가 빠진 사본이 생겨, 콘솔에서 로그아웃했을
 * 때만 서버에 출입증이 남는 상태가 된다.
 */
function renderConsoleNav() {
  const el = document.getElementById("nav");
  if (!el) return;
  el.className = "nav";
  el.innerHTML = `
    <a class="brand" href="/admin/index.html">csquiz 관리</a>
    <a href="/">← 학습 화면으로</a>
    <span class="spacer"></span>
    ${authAreaHtml()}`;
  wireLogout();
}

/** 관리 화면 사이를 오가는 링크 줄. 예전 탭 버튼과 같은 모양(.tabs)을 쓴다. */
function renderAdminNav(active) {
  document.getElementById("adminNav").innerHTML = ADMIN_PAGES.map(([key, label, href]) =>
    `<a class="tab-btn ${key === active ? "active" : ""}" href="${href}">${label}
       <span id="badge-${key}" class="badge gray" hidden></span></a>`).join("");
}

/**
 * 네비의 배지 셋을 갱신한다 — 검수 대기 2종 + 주제 범위 수.
 *
 * <p>배지를 <b>모든 페이지</b>에서 부르는 것은 의도다. "검수할 게 남았다"는 지금 보고 있는
 * 화면과 무관하게 알아야 하는 정보이고, 페이지를 옮길 때마다 최신값이 보인다.
 * 요청 3회가 붙지만 예전 탭 방식도 진입 시 같은 수를 불렀다.
 */
async function refreshAdminBadges() {
  // 셋을 한꺼번에 보낸다 — 순서대로 기다리면 페이지가 뜨는 데 왕복 3번이 그대로 더해진다.
  const [problems, documents, topics] = await Promise.all([
    countOf("/api/admin/llm-problems/pending-count"),
    countOf("/api/admin/llm-documents/pending-count"),
    countOf("/api/admin/topic-queue/count"),
  ]);

  // 검수 배지는 문제·문서 대기를 합쳐 하나로 보여 준다 — 한 페이지가 둘 다 다루므로
  // 배지도 하나여야 "저기 들어가면 할 일이 있다"가 정확해진다.
  //
  // 이 화면이 검수만 하게 된 뒤로(2026-08-29) 이 숫자가 곧 "읽어야 할 건수"가 됐다.
  // 예전에는 같은 페이지에 생성 폼이 있어서 배지가 가리키는 곳과 화면이 어긋났다.
  setBadge("llm", problems + documents, false);

  // 주제 범위는 뜻이 반대다: 0이면 배치가 모델 자동 선택으로 돌아가므로 0일 때 눈에 띄게 띄운다.
  // 주제 범위가 생성 화면 안으로 들어가면서 배지도 그쪽에 붙는다.
  setBadge("generate", topics === 0 ? "범위 없음" : null, topics === 0);
}

/** 배지 하나 — 값이 null이거나 0이면 숨긴다(0을 붙여 두면 늘 시끄럽다). */
function setBadge(key, value, highlight) {
  const el = document.getElementById("badge-" + key);
  if (!el) return;
  const hide = value === null || value === 0;
  el.textContent = value;
  el.className = highlight ? "badge orange" : "badge gray";
  el.hidden = hide;
}

/** 배지용 개수 조회. 실패하면 0 — 배지는 장식이라 화면 기능을 막지 않는다. */
async function countOf(path) {
  try {
    const { count } = await api(path);
    return count;
  } catch (e) {
    return 0;
  }
}

/* ── 페이지 사이 이동 ──────────────────────────────────────────
 * 예전에는 탭 전환(switchTab)이었다. 이제는 진짜 페이지 이동이라 <b>상태를 주소로</b>
 * 넘긴다 — 쿼리스트링에 담으면 새로고침해도, 링크를 복사해 둬도 같은 화면이 열린다.
 * 탭 시절의 해시(#llm)로는 못 하던 것이다. */

/** 문제 수정 화면으로 — 어느 페이지에서든 "이 문제를 고치자"의 단일 입구. */
function goProblemEdit(id) {
  location.href = "/admin/problems.html?edit=" + id;
}

/**
 * 검수 화면으로 — 어느 목록을 어떤 필터로 열지 넘긴다({@code kind}, {@code status}).
 *
 * <p>생성 폼을 채우는 용도({@code domain}, {@code difficulty})는 {@link goGenerate}로 갈라졌다
 * (2026-08-29). 한 함수가 두 화면을 가리키고 있으면 어느 쪽으로 가는지 호출부만 봐서는 모른다.
 */
function goLlm(params) {
  location.href = "/admin/llm.html?" + new URLSearchParams(params).toString();
}

/** 생성 화면으로 — 현황판의 빈 칸에서 "이 칸을 채우자"로 넘어올 때. 폼만 채우고 호출은 안 한다. */
function goGenerate(params) {
  location.href = "/admin/generate.html?" + new URLSearchParams(params).toString();
}

/** 주소의 쿼리 파라미터 — 없으면 null. */
function param(name) {
  return new URLSearchParams(location.search).get(name);
}

/* ── 위험 동작(삭제) 2단계 확인 ──
 * confirm() 같은 브라우저 팝업 대신 "버튼을 한 번 더 누르면 실행" 방식.
 * 팝업은 흐름을 끊고, 자동화 도구·테스트도 막는다. 3초 지나면 원래대로 돌아간다. */
function armedDelete(btn, onConfirm, label = "정말 삭제?") {
  // 확정(두 번째 클릭)이면 되돌리기 타이머를 먼저 끈다.
  //
  // 끄지 않으면 3초 뒤 타이머가 깨어나 <b>확정 당시의 문구</b>를 다시 써 버린다.
  // 삭제 버튼은 실행되면 행이 통째로 사라져 눈에 안 띄었지만, 일괄 승인처럼 버튼이
  // 화면에 남는 자리에서는 드러난다 — 승인이 끝나 선택이 0건인데 버튼만
  // "선택 4건 승인"으로 되살아났다(2026-08-20 브라우저 확인에서 발견).
  // 문구의 주인은 그리는 쪽(syncBulkBar 등)이고, 여기서는 잠시 빌렸다 돌려줄 뿐이다.
  if (btn.dataset.armed) {
    clearTimeout(Number(btn.dataset.armedTimer));
    delete btn.dataset.armed;
    delete btn.dataset.armedTimer;
    onConfirm();
    return;
  }
  btn.dataset.armed = "1";
  const original = btn.textContent;
  btn.textContent = label;
  btn.dataset.armedTimer = String(setTimeout(() => {
    delete btn.dataset.armed;
    delete btn.dataset.armedTimer;
    btn.textContent = original;
  }, 3000));
}

/* ── 클립보드 ─────────────────────────────────────────────────
 * 개념 문서를 블로그에 옮겨 붙이려고 만들었다(2026-08-23). 지금까지는 문서 원문을
 * 꺼내려면 generated/documents/*.json에서 contentMd 필드를 뽑거나 DB를 직접 뒤져야 했는데,
 * 둘 다 명령을 외워야 하고 <b>승인 화면에서 고친 내용이 반영되지 않는 쪽(파일)을 보기 쉽다</b>.
 * 화면은 이미 본문을 들고 있으니 클립보드에 넣기만 하면 된다. */

/**
 * 텍스트를 클립보드에 넣고 <b>버튼 문구로</b> 결과를 알린다.
 *
 * <p><b>왜 alert이 아니라 버튼 문구인가.</b> 이 화면의 위험 동작 확인({@code armedDelete})이
 * 이미 같은 방식을 쓴다 — 브라우저 팝업은 흐름을 끊고 자동화 도구도 막는다.
 * 복사는 위험하지도 않은 동작이라 더더욱 팝업을 띄울 이유가 없다.
 *
 * <p><b>왜 성공/실패를 굳이 보여 주나.</b> 복사는 <b>실패해도 화면이 그대로</b>라
 * 알려 주지 않으면 사용자가 붙여넣기를 해 보고 나서야 안다. 그때는 이미 편집기로
 * 넘어간 뒤라 원인을 찾기 어렵다.
 *
 * <p><b>대체 경로를 함께 두는 이유.</b> {@code navigator.clipboard}는 보안 컨텍스트
 * (https 또는 localhost)에서만 산다. 로컬 개발은 localhost라 문제없지만, 같은 PC를
 * 사설 IP로 열어 두고 폰에서 접속하는 순간 {@code undefined}가 된다 — 그때 조용히
 * 아무 일도 일어나지 않는 대신 옛 방식(숨긴 textarea + execCommand)으로 넘어간다.
 * execCommand는 폐기 예정이지만 대체 수단이 없고, 실패해도 잃을 것이 없다.
 */
async function copyToClipboard(btn, text, okLabel = "복사됨") {
  // 연달아 누르면 "복사됨"을 원래 문구로 오해하고 굳어 버린다 — armedDelete가 겪은 사고와 같다.
  const original = btn.dataset.copyOriginal ?? btn.textContent;
  btn.dataset.copyOriginal = original;
  clearTimeout(Number(btn.dataset.copyTimer));

  let ok;
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      ok = true;
    } else {
      ok = copyByTextarea(text);
    }
  } catch (e) {
    // 권한 거부·포커스 없음 등으로 최신 API가 거절할 때가 있다. 옛 방식은 통하는 경우가 많다.
    ok = copyByTextarea(text);
  }

  btn.textContent = ok ? okLabel : "복사 실패";
  btn.dataset.copyTimer = String(setTimeout(() => {
    btn.textContent = btn.dataset.copyOriginal;
    delete btn.dataset.copyOriginal;
    delete btn.dataset.copyTimer;
  }, 1800));
  return ok;
}

/**
 * 옛 복사 방식 — 화면 밖 textarea에 담아 선택한 뒤 실행 명령을 부른다.
 *
 * <p>{@code display:none}이면 선택이 안 되므로 <b>화면 밖으로 밀어내는</b> 방식을 쓴다.
 * {@code readOnly}는 모바일에서 키보드가 올라오는 것을 막는다.
 */
function copyByTextarea(text) {
  const ta = document.createElement("textarea");
  ta.value = text;
  ta.readOnly = true;
  ta.style.cssText = "position:fixed; left:-9999px; top:0";
  document.body.appendChild(ta);
  ta.select();
  let ok = false;
  try {
    ok = document.execCommand("copy");
  } catch (e) {
    ok = false;
  }
  document.body.removeChild(ta);
  return ok;
}

function showError(el, e) {
  const details = (e.fieldErrors || []).map(f => `<li>${escapeHtml(f.reason)}</li>`).join("");
  el.innerHTML = `<div class="alert error">${escapeHtml(e.message)}${details ? `<ul style="margin:6px 0 0">${details}</ul>` : ""}</div>`;
}

function showOk(el, text) {
  el.innerHTML = `<div class="alert info">${escapeHtml(text)}</div>`;
  setTimeout(() => { el.innerHTML = ""; }, 2500);
}

/** 긴 문자열 자르기 — 표에서 지문이 줄을 다 먹지 않게. */
function cut(s, n) {
  return s.length > n ? s.substring(0, n) + "…" : s;
}

/** 2026-08-15T21:53:… → 08-15. 관리 화면의 표에서 초 단위는 읽히지 않고 폭만 먹는다. */
function shortDate(iso) {
  return iso ? iso.slice(5, 10) : "";
}
