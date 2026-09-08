/* 관리 화면 공통 — 가드·화면 간 이동·삭제 확인·복사 같은 도우미.
 *
 * [셸은 여기 없다] 상단 띠·기둥·배지는 admin/js/admin-shell.js로 옮겼다(2026-09-06 개편).
 * 이 파일은 <화면이 무엇을 하든 필요한 것>만 맡는다 — 셸이 사이드바에서 다른 모양으로
 * 바뀌어도 여기는 안 바뀌어야 한다.
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
 * 관리 화면 공통 초기화 — 가드 → 셸 순서로 한 번에 처리한다.
 *
 * <p>가드가 먼저인 이유: 관리자가 아니면 셸을 그릴 이유가 없다. 그리고 이 가드는
 * <b>장식</b>이다 — 진짜 방어는 서버 두 겹이다(정적 파일은 AdminGateFilter의 쿠키 검사,
 * API는 SecurityConfig의 hasRole). 여기서 막는 것은 "로그아웃한 채 뒤로가기로 돌아온"
 * 상황에서 빈 화면 대신 안내를 보여 주기 위해서다.
 *
 * <p>셸은 <b>가드를 통과한 뒤에만</b> 그린다. 관리자가 아닌 사람에게 관리 메뉴 이름을
 * 늘어놓을 이유가 없다 — 데이터가 새지는 않지만 "무엇이 있는지"는 그 자체로 정보다.
 *
 * @param active ADMIN_MENUS의 key(admin-shell.js)
 * @returns 관리자면 true — 호출부는 이 값이 false면 데이터 적재를 건너뛴다
 */
function initAdminPage(active) {
  if (!isAdmin()) {
    document.getElementById("guard").innerHTML =
      `<div class="alert error">관리자 전용 페이지입니다. 관리자 계정으로 <a href="/login.html">로그인</a>해 주세요.</div>`;
    return false;
  }
  document.getElementById("adminUi").hidden = false;
  renderAdminShell(active);
  refreshAdminBadges();
  return true;
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

/* ── 되돌리기 어려운 동작의 2단계 확인 ──
 * confirm() 같은 브라우저 팝업 대신 "버튼을 한 번 더 누르면 실행" 방식.
 * 팝업은 흐름을 끊고, 자동화 도구·테스트도 막는다. 3초 지나면 원래대로 돌아간다.
 *
 * [이름을 armedDelete에서 바꿨다 — 2026-09-08]
 * 이미 삭제 아닌 곳에서 쓰이고 있었다 — 검수함의 일괄 승인, 그리고 이번에 더한
 * 문제 생성(누르면 Claude 요금이 나간다). 하는 일은 "되돌리기 어려운 것을 한 번 더 묻는 것"이지
 * 삭제가 아니다. 이름이 실제와 어긋나면 다음 사람이 "삭제가 아닌데 이걸 써도 되나"를
 * 매번 되묻게 된다. */
function armedAction(btn, onConfirm, label = "정말 삭제?") {
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
 * <p><b>왜 alert이 아니라 버튼 문구인가.</b> 이 화면의 위험 동작 확인({@code armedAction})이
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
  // 연달아 누르면 "복사됨"을 원래 문구로 오해하고 굳어 버린다 — armedAction이 겪은 사고와 같다.
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
