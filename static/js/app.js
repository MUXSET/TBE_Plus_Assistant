/* =================================================================
 *  static/js/app.js
 *  Version: 2.3.0
 *  Description: 统一前端控制器（支持桌面 pywebview 原生桥接 + 移动端浏览器适配）
 * ================================================================= */

const App = (() => {
  const RING_CIRCUMFERENCE = 2 * Math.PI * 21; // r = 21 -> 131.95

  const state = {
    view: "dashboard",
    preset: "month",
    themeMode: "System",
    snapshot: null,
    logCursor: null,
    logFilter: "all",
    logQuery: "",
    autoScroll: true,
    rawLogs: [],
    channels: [],
    defaultChannels: [],
    settingsLoaded: false,
    confirmResolver: null,
    verifyPollTimer: null,
    themeLockUntil: 0,
    lastAccSig: "",
    lastPopSig: "",
  };

  const MAX_LOGS = 1200;

  const isDesktop = () =>
    document.body.dataset.isDesktop === "true" &&
    Boolean(window.pywebview && window.pywebview.api);

  // ==================== 主题与滑块计算 ====================
  const sysDarkQuery = window.matchMedia("(prefers-color-scheme: dark)");

  function applyThemeVisual(mode) {
    state.themeMode = mode || "System";
    const resolved =
      state.themeMode === "System"
        ? sysDarkQuery.matches
          ? "dark"
          : "light"
        : state.themeMode.toLowerCase();
    document.documentElement.setAttribute("data-theme", resolved);

    const idxMap = { System: 0, Dark: 1, Light: 2 };
    const idx = idxMap[state.themeMode] ?? 0;
    const thumb = document.getElementById("theme-thumb");
    if (thumb) {
      thumb.style.transform = `translateX(${idx * 100}%)`;
    }
    document.querySelectorAll(".theme-opt").forEach((btn) => {
      btn.classList.toggle("active", btn.dataset.mode === state.themeMode);
    });
  }

  sysDarkQuery.addEventListener("change", () => {
    if (state.themeMode === "System") {
      applyThemeVisual("System");
    }
  });

  async function setTheme(mode) {
    state.themeLockUntil = Date.now() + 3000;
    applyThemeVisual(mode);
    try {
      await apiPost("/api/theme", { mode });
    } catch (_) {}
  }

  function positionPresetSlider() {
    const seg = document.getElementById("preset-seg");
    const slider = document.getElementById("preset-slider");
    if (!seg || !slider) return;
    const active = seg.querySelector(".seg-btn.active");
    if (!active) return;
    slider.style.width = `${active.offsetWidth}px`;
    slider.style.transform = `translateX(${active.offsetLeft}px)`;
  }

  // ==================== HTTP 与原生桥接 ====================
  async function apiGet(url) {
    const res = await fetch(url, { cache: "no-store" });
    if (res.status === 401) {
      showPinOverlay(true);
      throw new Error("AUTH_REQUIRED");
    }
    return res;
  }

  async function apiPost(url, body = {}, method = "POST") {
    const res = await fetch(url, {
      method,
      headers: { "Content-Type": "application/json" },
      body: method === "DELETE" ? undefined : JSON.stringify(body),
    });
    if (res.status === 401) {
      showPinOverlay(true);
      throw new Error("AUTH_REQUIRED");
    }
    return res;
  }

  function showToast(msg, type = "info") {
    const wrap = document.getElementById("toast-container");
    if (!wrap || !msg) return;
    const el = document.createElement("div");
    el.className = `toast ${type}`;
    el.textContent = msg;
    wrap.appendChild(el);
    setTimeout(() => {
      el.style.opacity = "0";
      el.style.transform = "translateY(6px)";
      el.style.transition = "opacity 0.18s ease, transform 0.18s ease";
      setTimeout(() => el.remove(), 200);
    }, 3200);
  }

  async function copyToClipboard(text, successMsg = "已复制到剪贴板") {
    if (!text) return;
    try {
      if (isDesktop() && window.pywebview.api.copy_text) {
        const res = await window.pywebview.api.copy_text(text);
        if (res && res.ok) {
          showToast(successMsg, "ok");
          return;
        }
      }
      if (navigator.clipboard && navigator.clipboard.writeText) {
        await navigator.clipboard.writeText(text);
        showToast(successMsg, "ok");
        return;
      }
    } catch (_) {}
    const ta = document.createElement("textarea");
    ta.value = text;
    ta.style.position = "fixed";
    ta.style.opacity = "0";
    document.body.appendChild(ta);
    ta.select();
    try {
      document.execCommand("copy");
      showToast(successMsg, "ok");
    } catch (_) {
      showToast("复制失败，请手动选中复制", "err");
    } finally {
      ta.remove();
    }
  }

  // ==================== 视图与气泡控制 ====================
  function switchView(viewName) {
    state.view = viewName;
    document.querySelectorAll(".view").forEach((v) => {
      v.classList.toggle("active", v.id === `view-${viewName}`);
    });
    document.querySelectorAll(".nav-item, .mnav-item").forEach((b) => {
      b.classList.toggle("active", b.dataset.view === viewName);
    });
    if (viewName === "dashboard") {
      requestAnimationFrame(positionPresetSlider);
    } else if (viewName === "settings") {
      loadSettings();
    }
  }

  function toggleAccountPopover(ev) {
    if (ev) ev.stopPropagation();
    const snap = state.snapshot;
    if (snap && (snap.is_busy || snap.auto_mode)) {
      showToast("任务或挂机运行中，暂不可切换账号", "err");
      return;
    }
    const widget = document.getElementById("account-widget");
    widget.classList.toggle("open");
  }

  function closeAccountPopover() {
    document.getElementById("account-widget")?.classList.remove("open");
  }

  document.addEventListener("click", (e) => {
    const widget = document.getElementById("account-widget");
    if (widget && !widget.contains(e.target)) {
      widget.classList.remove("open");
    }
  });

  // ==================== 状态快照渲染 ====================
  function renderSnapshot(snap) {
    state.snapshot = snap;

    if (
      Date.now() > state.themeLockUntil &&
      snap.appearance_mode &&
      snap.appearance_mode !== state.themeMode
    ) {
      applyThemeVisual(snap.appearance_mode);
    }

    const accs = snap.accounts || [];
    const username = snap.username || "";
    const busy = Boolean(snap.is_busy);
    const scanning = Boolean(snap.is_scanning || snap.is_exporting);

    // 侧边栏导航徽章与账号卡
    document.getElementById("nav-acc-count").textContent = String(accs.length);
    document.getElementById("sidebar-avatar").textContent = username
      ? username.slice(-2).toUpperCase()
      : "--";
    document.getElementById("sidebar-acc-name").textContent = username || "未配置账号";
    document.getElementById("sidebar-acc-cache").textContent = `已赞缓存 ${snap.cache_size || 0} 篇`;
    document.getElementById("sidebar-multi-pill").style.display = snap.multi_account_auto
      ? "inline-block"
      : "none";

    // 下拉气泡列表（仅数据变更时重绘）
    const popSig = JSON.stringify(accs.map((a) => [a.username, a.is_active, a.cache_size]));
    if (popSig !== state.lastPopSig) {
      state.lastPopSig = popSig;
      const popList = document.getElementById("popover-acc-list");
      const popFrag = document.createDocumentFragment();
      if (accs.length === 0) {
        const empty = document.createElement("div");
        empty.style.cssText = "padding:8px;font-size:12px;color:var(--text-muted);text-align:center";
        empty.textContent = "暂无已添加账号";
        popFrag.appendChild(empty);
      } else {
        for (const a of accs) {
          const btn = document.createElement("button");
          btn.className = `popover-item${a.is_active ? " active" : ""}`;
          btn.onclick = () => {
            closeAccountPopover();
            switchAccount(a.username);
          };
          const left = document.createElement("span");
          left.textContent = a.username;
          const right = document.createElement("span");
          right.style.cssText = "font-size:11px;color:var(--text-muted)";
          right.textContent = a.is_active ? "当前" : `${a.cache_size} 篇`;
          btn.append(left, right);
          popFrag.appendChild(btn);
        }
      }
      popList.replaceChildren(popFrag);
    }

    // 挂机主按钮与停止按钮
    const autoBtn = document.getElementById("btn-auto-toggle");
    const autoText = document.getElementById("btn-auto-text");
    const autoIconUse = document.querySelector("#btn-auto-icon use");
    if (snap.auto_mode) {
      autoBtn.classList.add("running");
      autoText.textContent = "停止自动挂机";
      autoIconUse.setAttribute("href", "#i-stop");
    } else {
      autoBtn.classList.remove("running");
      autoText.textContent = "启动自动挂机";
      autoIconUse.setAttribute("href", "#i-radar");
    }
    document.getElementById("btn-stop-task").classList.toggle("show", scanning);

    // 状态胶囊与倒计时
    const statusDot = document.getElementById("status-dot");
    statusDot.className = "pulse-dot" + (busy ? " busy" : snap.auto_mode ? " active" : "");
    document.getElementById("status-text").textContent = snap.status_text || "空闲待机";

    const countdown = document.getElementById("next-scan-tag");
    if (snap.next_auto_scan_text) {
      countdown.style.display = "inline-block";
      countdown.textContent = snap.next_auto_scan_text;
    } else {
      countdown.style.display = "none";
    }

    // 顶部快捷操作按钮
    document.getElementById("btn-refresh-token").disabled = busy;
    document.getElementById("btn-export-report").disabled = scanning;
    const reportBtn = document.getElementById("btn-report-ready");
    if (snap.has_report) {
      reportBtn.style.display = "inline-flex";
      document.getElementById("btn-report-text").textContent = isDesktop()
        ? "查看月报"
        : "下载月报 CSV";
    } else {
      reportBtn.style.display = "none";
    }

    // 4 张 KPI 卡片
    document.getElementById("kpi-total").textContent =
      snap.total !== null && snap.total !== undefined ? String(snap.total) : "---";
    document.getElementById("kpi-total-sub").textContent = `覆盖 ${snap.channel_count || 0} 个监控频道`;

    document.getElementById("kpi-liked").textContent =
      snap.liked !== null && snap.liked !== undefined ? String(snap.liked) : "---";
    const rate = snap.completion_rate;
    const ringFill = document.getElementById("ring-progress");
    const ringPct = document.getElementById("ring-pct");
    if (rate !== null && rate !== undefined) {
      const offset = RING_CIRCUMFERENCE * (1 - Math.min(100, Math.max(0, rate)) / 100);
      ringFill.style.strokeDashoffset = offset.toFixed(2);
      ringPct.textContent = `${Math.round(rate)}%`;
      document.getElementById("kpi-liked-sub").textContent =
        snap.pending_count === 0 ? "本月已全部完成点赞" : `待赞 ${snap.pending_count} 篇`;
    } else {
      ringFill.style.strokeDashoffset = RING_CIRCUMFERENCE.toFixed(2);
      ringPct.textContent = "--";
      document.getElementById("kpi-liked-sub").textContent = "待核算进度";
    }

    const ls = snap.last_sweep || {};
    document.getElementById("kpi-last").textContent = snap.last_scan || "从未";
    document.getElementById("kpi-last-sub").textContent = `新赞 ${ls.liked || 0} · 跳过 ${ls.skipped || 0}`;
    document.getElementById("card-last-scan").title = ls.last_sweep_time
      ? `精确时间: ${ls.last_sweep_time}`
      : "尚未执行过扫描";

    const tokDot = document.getElementById("kpi-token-dot");
    tokDot.className = `status-dot-lg ${snap.token_color || "orange"}`;
    document.getElementById("kpi-token-val").textContent = snap.token_status || "检测中";
    document.getElementById("kpi-token-sub").textContent = snap.is_refreshing_token
      ? "正在通过无头浏览器刷新..."
      : `刷新: ${snap.token_refresh_time_str || "从未"}`;
    document.getElementById("card-token").title = `预计下次: ${snap.token_next_refresh_str || "未知"}\n点击立即校验凭据`;

    // 扫描按钮 & 分段控制器锁定
    const scanBtn = document.getElementById("btn-start-scan");
    scanBtn.disabled = scanning;
    document.getElementById("btn-scan-text").textContent = snap.is_scanning
      ? "扫描中..."
      : snap.is_exporting
      ? "导出中..."
      : "开始扫描";
    document.getElementById("preset-seg").classList.toggle("disabled", scanning);

    // 进度抽屉
    const progDrawer = document.getElementById("progress-drawer");
    const prog = snap.scan_progress;
    if (scanning && prog) {
      progDrawer.classList.add("open");
      const pct = Math.round((prog.ratio || 0) * 100);
      const prefix = prog.username && snap.multi_account_auto ? `[${prog.username}] ` : "";
      const extra =
        prog.stage === "like" && prog.total
          ? ` · 新赞 ${prog.liked || 0} · 跳过 ${prog.skipped || 0}`
          : "";
      document.getElementById("prog-msg").textContent = `${prefix}${prog.message || "处理中..."}${extra}`;
      document.getElementById("prog-pct").textContent = `${pct}%`;
      document.getElementById("prog-fill").style.width = `${pct}%`;
    } else {
      progDrawer.classList.remove("open");
    }

    // 远程面板状态卡
    const winfo = snap.web_info || {};
    const rState = document.getElementById("remote-state");
    const rAddr = document.getElementById("remote-addr");
    const rOpen = document.getElementById("btn-remote-open");
    const rCopy = document.getElementById("btn-remote-copy");
    if (!winfo.enabled) {
      rState.className = "remote-state off";
      rState.innerHTML = '<i class="dot"></i> 已关闭';
      rAddr.textContent = "可在偏好设置开启";
      rOpen.disabled = true;
      rCopy.disabled = true;
    } else if (winfo.running) {
      rState.className = "remote-state";
      rState.innerHTML = '<i class="dot"></i> 运行中';
      const showUrl = (winfo.lan_url || winfo.url || "").replace(/^https?:\/\//, "");
      rAddr.textContent = showUrl || `localhost:${winfo.port || 5050}`;
      rOpen.disabled = false;
      rCopy.disabled = false;
    } else if (winfo.error) {
      rState.className = "remote-state err";
      rState.innerHTML = '<i class="dot"></i> 冲突';
      rAddr.textContent = winfo.error;
      rOpen.disabled = true;
      rCopy.disabled = true;
    } else {
      rState.className = "remote-state off";
      rState.innerHTML = '<i class="dot"></i> 未就绪';
      rAddr.textContent = `端口 ${winfo.port || 5050}`;
      rOpen.disabled = true;
      rCopy.disabled = true;
    }

    // 账号管理页渲染
    renderAccountsGrid(accs, busy || snap.auto_mode);

    // 设置页繁忙提醒
    const busyBanner = document.getElementById("settings-busy-banner");
    const saveBtn = document.getElementById("btn-save-settings");
    if (busyBanner && saveBtn) {
      const blocked = Boolean(snap.auto_mode || snap.is_scanning || snap.is_exporting);
      busyBanner.style.display = blocked ? "flex" : "none";
      saveBtn.disabled = blocked;
    }

    // 0 账号欢迎引导
    const onboard = document.getElementById("onboarding-overlay");
    if (accs.length === 0) {
      onboard.classList.add("show");
    } else {
      onboard.classList.remove("show");
    }
  }

  function renderAccountsGrid(accs, locked) {
    const grid = document.getElementById("accounts-grid");
    if (!grid) return;
    const accSig = JSON.stringify({ accs, locked, scanning: Boolean(state.snapshot?.is_scanning) });
    if (accSig === state.lastAccSig) return;
    state.lastAccSig = accSig;
    if (accs.length === 0) {
      grid.innerHTML =
        '<div class="card section-card" style="grid-column:1/-1;text-align:center;color:var(--text-muted)">暂无已配置账号，请在下方添加您的首个 TBE+ 账号</div>';
      return;
    }

    const frag = document.createDocumentFragment();
    for (const a of accs) {
      const card = document.createElement("div");
      card.className = `acc-card${a.is_active ? " active" : ""}`;

      const progressTxt =
        a.monthly_total !== null && a.monthly_total !== undefined
          ? `${a.monthly_liked ?? 0} / ${a.monthly_total}`
          : "---";

      card.innerHTML = `
        <div class="acc-card-top">
          <div class="avatar">${escapeHtml(a.username.slice(-2).toUpperCase())}</div>
          <div>
            <div class="acc-card-title">
              <span>${escapeHtml(a.username)}</span>
              ${a.is_active ? '<span class="pill-active">当前使用</span>' : ""}
            </div>
            <div class="acc-card-sub">${a.has_token ? "已缓存登录凭据" : "待登录获取凭据"}</div>
          </div>
        </div>
        <div class="acc-metrics">
          <div><div class="acc-metric-lbl">本月进度</div><div class="acc-metric-val">${progressTxt}</div></div>
          <div><div class="acc-metric-lbl">已赞缓存</div><div class="acc-metric-val">${a.cache_size} 篇</div></div>
          <div><div class="acc-metric-lbl">上次扫描</div><div class="acc-metric-val">${escapeHtml(a.last_scan || "从未")}</div></div>
        </div>
      `;

      const actions = document.createElement("div");
      actions.className = "acc-card-actions";

      if (!a.is_active) {
        const swBtn = document.createElement("button");
        swBtn.className = "btn-xs btn-xs-accent";
        swBtn.textContent = "设为当前";
        swBtn.disabled = locked;
        swBtn.onclick = () => switchAccount(a.username);
        actions.appendChild(swBtn);
      }

      const pwdBtn = document.createElement("button");
      pwdBtn.className = "btn-xs";
      pwdBtn.textContent = "改密";
      pwdBtn.onclick = () => prefillAccountPassword(a.username);
      actions.appendChild(pwdBtn);

      const clrBtn = document.createElement("button");
      clrBtn.className = "btn-xs";
      clrBtn.textContent = "清缓存";
      clrBtn.disabled = Boolean(state.snapshot?.is_scanning);
      clrBtn.onclick = () => clearAccountCache(a.username);
      actions.appendChild(clrBtn);

      const delBtn = document.createElement("button");
      delBtn.className = "btn-xs btn-xs-danger";
      delBtn.textContent = "删除";
      delBtn.disabled = locked;
      delBtn.onclick = () => deleteAccount(a.index, a.username);
      actions.appendChild(delBtn);

      card.appendChild(actions);
      frag.appendChild(card);
    }
    grid.replaceChildren(frag);
  }

  // ==================== 扫描范围与日期计算 ====================
  function pad2(n) {
    return String(n).padStart(2, "0");
  }
  function fmtDate(d) {
    return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
  }

  function computePresetRange(preset) {
    const now = new Date();
    const today = fmtDate(now);
    if (preset === "today") return [today, today];
    if (preset === "3days") {
      const d = new Date(now);
      d.setDate(d.getDate() - 2);
      return [fmtDate(d), today];
    }
    if (preset === "week") {
      const d = new Date(now);
      const day = d.getDay() || 7;
      d.setDate(d.getDate() - (day - 1));
      return [fmtDate(d), today];
    }
    if (preset === "month") {
      return [`${now.getFullYear()}-${pad2(now.getMonth() + 1)}-01`, today];
    }
    if (preset === "last_month") {
      const firstThis = new Date(now.getFullYear(), now.getMonth(), 1);
      const lastPrev = new Date(firstThis.getTime() - 86400000);
      const firstPrev = new Date(lastPrev.getFullYear(), lastPrev.getMonth(), 1);
      return [fmtDate(firstPrev), fmtDate(lastPrev)];
    }
    return null;
  }

  function initPresets() {
    const seg = document.getElementById("preset-seg");
    const now = new Date();
    document.getElementById("custom-start").value = `${now.getFullYear()}-${pad2(now.getMonth() + 1)}-01`;
    document.getElementById("custom-end").value = fmtDate(now);

    seg.addEventListener("click", (e) => {
      const btn = e.target.closest(".seg-btn");
      if (!btn || seg.classList.contains("disabled")) return;
      const preset = btn.dataset.preset;
      state.preset = preset;
      seg.querySelectorAll(".seg-btn").forEach((b) => b.classList.toggle("active", b === btn));
      positionPresetSlider();

      document.getElementById("custom-date-err").textContent = "";
      document.getElementById("custom-date-drawer").classList.toggle("open", preset === "custom");
    });

    if (typeof ResizeObserver !== "undefined") {
      new ResizeObserver(() => positionPresetSlider()).observe(seg);
    }
    window.addEventListener("resize", positionPresetSlider);
    requestAnimationFrame(positionPresetSlider);
  }

  // ==================== 业务操作动作 ====================
  async function startScan() {
    const errEl = document.getElementById("custom-date-err");
    errEl.textContent = "";
    let sd, ed;
    if (state.preset === "custom") {
      sd = document.getElementById("custom-start").value.trim();
      ed = document.getElementById("custom-end").value.trim();
      if (!sd || !ed) {
        errEl.textContent = "请选择完整的起始与结束日期";
        return;
      }
      if (sd > ed) {
        errEl.textContent = "起始日期不能晚于结束日期";
        return;
      }
    } else {
      [sd, ed] = computePresetRange(state.preset);
    }

    try {
      const res = await apiPost("/api/action", { action: "scan", start_date: sd, end_date: ed });
      const d = await res.json();
      showToast(d.msg || (d.ok ? "扫描已启动" : "无法启动扫描"), d.ok ? "ok" : "err");
      fetchStatus();
    } catch (e) {
      if (e.message !== "AUTH_REQUIRED") showToast("请求失败", "err");
    }
  }

  async function toggleAuto() {
    try {
      const res = await apiPost("/api/action", { action: "toggle_auto" });
      const d = await res.json();
      showToast(d.msg || "操作已执行", d.ok ? "ok" : "err");
      fetchStatus();
    } catch (e) {
      if (e.message !== "AUTH_REQUIRED") showToast("操作失败", "err");
    }
  }

  async function stopTask() {
    try {
      const res = await apiPost("/api/action", { action: "stop" });
      const d = await res.json();
      showToast(d.msg || "已发送停止信号", "info");
      fetchStatus();
    } catch (e) {
      if (e.message !== "AUTH_REQUIRED") showToast("请求失败", "err");
    }
  }

  async function refreshToken() {
    try {
      const res = await apiPost("/api/action", { action: "token" });
      const d = await res.json();
      showToast(d.msg || "刷新请求已提交", d.ok ? "ok" : "err");
      fetchStatus();
    } catch (e) {
      if (e.message !== "AUTH_REQUIRED") showToast("请求失败", "err");
    }
  }

  async function checkToken() {
    if (state.snapshot?.is_busy) {
      showToast("任务执行中，暂不可检测凭据", "err");
      return;
    }
    try {
      const res = await apiPost("/api/action", { action: "check_token" });
      const d = await res.json();
      showToast(d.msg || "开始校验", d.ok ? "info" : "err");
      fetchStatus();
    } catch (e) {
      if (e.message !== "AUTH_REQUIRED") showToast("请求失败", "err");
    }
  }

  async function exportReport() {
    try {
      const res = await apiPost("/api/action", { action: "export" });
      const d = await res.json();
      showToast(d.msg || "月报导出已触发", d.ok ? "ok" : "err");
      fetchStatus();
    } catch (e) {
      if (e.message !== "AUTH_REQUIRED") showToast("请求失败", "err");
    }
  }

  async function openOrDownloadReport() {
    if (isDesktop() && window.pywebview.api.reveal_report) {
      const res = await window.pywebview.api.reveal_report();
      if (!res?.ok) showToast(res?.msg || "无法定位月报文件", "err");
      return;
    }
    window.location.href = "/api/report/download";
  }

  async function switchAccount(username) {
    if (!username) return;
    try {
      const res = await apiPost("/api/action", { action: "switch_account", username });
      const d = await res.json();
      showToast(d.msg || "已切换账号", d.ok ? "ok" : "err");
      fetchStatus();
    } catch (e) {
      if (e.message !== "AUTH_REQUIRED") showToast("切换失败", "err");
    }
  }

  async function clearAccountCache(username) {
    const ok = await askConfirm("清空点赞缓存", `确定清空账号 ${username} 的本地已赞记录缓存吗？清空后下次扫描将重新核验服务端状态。`);
    if (!ok) return;
    try {
      const res = await apiPost("/api/action", { action: "clear_cache", username });
      const d = await res.json();
      showToast(d.msg || "已清空缓存", d.ok ? "ok" : "err");
      fetchStatus();
    } catch (e) {
      if (e.message !== "AUTH_REQUIRED") showToast("清空失败", "err");
    }
  }

  async function deleteAccount(index, username) {
    const ok = await askConfirm("删除账号", `确定永久删除账号 ${username} 及其本地缓存吗？`);
    if (!ok) return;
    try {
      const res = await apiPost(`/api/accounts/${index}`, {}, "DELETE");
      const d = await res.json();
      showToast(d.msg || "已删除", d.ok ? "ok" : "err");
      fetchStatus();
    } catch (e) {
      if (e.message !== "AUTH_REQUIRED") showToast("删除失败", "err");
    }
  }

  function prefillAccountPassword(username) {
    switchView("accounts");
    document.getElementById("acc-user").value = username;
    const passEl = document.getElementById("acc-pass");
    passEl.value = "";
    passEl.focus();
    const msgEl = document.getElementById("acc-verify-msg");
    msgEl.className = "verify-status";
    msgEl.textContent = `已填充工号 ${username}，请输入新密码后点击「验证并保存」`;
  }

  // ==================== 异步账号验证轮询 ====================
  function startVerifyFlow(username, password, msgEl, btnEl, onSuccess) {
    if (state.verifyPollTimer) {
      clearTimeout(state.verifyPollTimer);
      state.verifyPollTimer = null;
    }
    btnEl.disabled = true;
    msgEl.className = "verify-status warn";
    msgEl.textContent = "正在启动无头浏览器验证登录，请稍候...";

    apiPost("/api/accounts/verify", { username, password })
      .then((r) => r.json())
      .then((d) => {
        if (!d.ok || !d.job_id) {
          btnEl.disabled = false;
          msgEl.className = "verify-status err";
          msgEl.textContent = d.msg || "提交验证失败";
          return;
        }
        pollVerifyJob(d.job_id, msgEl, btnEl, onSuccess);
      })
      .catch(() => {
        btnEl.disabled = false;
        msgEl.className = "verify-status err";
        msgEl.textContent = "网络请求失败";
      });
  }

  function pollVerifyJob(jobId, msgEl, btnEl, onSuccess) {
    apiGet(`/api/accounts/verify/${jobId}`)
      .then((r) => r.json())
      .then((d) => {
        if (!d.ok) {
          btnEl.disabled = false;
          msgEl.className = "verify-status err";
          msgEl.textContent = d.msg || "查询验证状态失败";
          return;
        }
        if (d.status === "running") {
          state.verifyPollTimer = setTimeout(
            () => pollVerifyJob(jobId, msgEl, btnEl, onSuccess),
            700
          );
          return;
        }
        btnEl.disabled = false;
        if (d.status === "success") {
          msgEl.className = "verify-status ok";
          msgEl.textContent = d.msg || "验证成功！";
          showToast(d.msg || "账号验证成功", "ok");
          fetchStatus();
          if (onSuccess) onSuccess();
        } else {
          msgEl.className = "verify-status err";
          msgEl.textContent = d.msg || "验证失败，请检查工号密码";
        }
      })
      .catch(() => {
        btnEl.disabled = false;
        msgEl.className = "verify-status err";
        msgEl.textContent = "验证轮询中断";
      });
  }

  function submitAccountForm(ev) {
    ev.preventDefault();
    const u = document.getElementById("acc-user").value.trim();
    const p = document.getElementById("acc-pass").value.trim();
    const msgEl = document.getElementById("acc-verify-msg");
    const btnEl = document.getElementById("btn-acc-verify");
    if (!u || !p) {
      msgEl.className = "verify-status err";
      msgEl.textContent = "请输入完整的工号和密码";
      return;
    }
    startVerifyFlow(u, p, msgEl, btnEl, () => {
      document.getElementById("acc-user").value = "";
      document.getElementById("acc-pass").value = "";
    });
  }

  function submitOnboarding(ev) {
    ev.preventDefault();
    const u = document.getElementById("ob-user").value.trim();
    const p = document.getElementById("ob-pass").value.trim();
    const msgEl = document.getElementById("ob-msg");
    const btnEl = document.getElementById("btn-ob-submit");
    if (!u || !p) {
      msgEl.className = "verify-status err";
      msgEl.textContent = "请输入工号和密码";
      return;
    }
    startVerifyFlow(u, p, msgEl, btnEl, () => {
      document.getElementById("ob-user").value = "";
      document.getElementById("ob-pass").value = "";
    });
  }

  // ==================== 偏好设置读写 ====================
  async function loadSettings() {
    try {
      const res = await apiGet("/api/settings");
      const d = await res.json();
      document.getElementById("set-scan-iv").value = d.scan_interval_hours;
      document.getElementById("set-token-iv").value = d.token_refresh_interval_hours;
      document.getElementById("set-auto-check").checked = Boolean(d.auto_check_monthly_total);
      document.getElementById("set-disable-proxy").checked = Boolean(d.disable_system_proxy);
      document.getElementById("set-multi-auto").checked = Boolean(d.multi_account_auto);

      const w = d.web_panel || {};
      document.getElementById("set-web-enabled").checked = Boolean(w.enabled);
      document.getElementById("set-web-lan").checked = Boolean(w.allow_lan);
      document.getElementById("set-web-port").value = w.port || 5050;
      document.getElementById("set-web-pin").value = w.access_pin || "";

      state.channels = (d.channels || []).map((c) => ({ ...c }));
      state.defaultChannels = (d.default_channels || []).map((c) => ({ ...c }));
      state.settingsLoaded = true;
      renderChannels();
    } catch (e) {
      if (e.message !== "AUTH_REQUIRED") showToast("加载设置失败", "err");
    }
  }

  function renderChannels() {
    const list = document.getElementById("channels-list");
    if (!list) return;
    if (state.channels.length === 0) {
      list.innerHTML =
        '<div style="padding:10px;text-align:center;color:var(--text-muted);font-size:12px">暂无监控频道，请添加或点击右上角恢复默认</div>';
      return;
    }
    const frag = document.createDocumentFragment();
    state.channels.forEach((ch, idx) => {
      const item = document.createElement("div");
      item.className = "channel-item";
      item.innerHTML = `
        <div>
          <div class="channel-name">${escapeHtml(ch.name)}</div>
          <div class="channel-id">${escapeHtml(ch.id)}</div>
        </div>
      `;
      const del = document.createElement("button");
      del.type = "button";
      del.className = "icon-btn-sm";
      del.title = "移除此频道";
      del.innerHTML = '<svg class="ic ic-xs"><use href="#i-x"/></svg>';
      del.onclick = () => {
        state.channels.splice(idx, 1);
        renderChannels();
      };
      item.appendChild(del);
      frag.appendChild(item);
    });
    list.replaceChildren(frag);
  }

  function addChannel() {
    const nameEl = document.getElementById("ch-name");
    const idEl = document.getElementById("ch-id");
    const name = nameEl.value.trim();
    const cid = idEl.value.trim();
    if (!name || !cid) {
      showToast("请填写完整的频道名称与频道 ID", "err");
      return;
    }
    if (state.channels.some((c) => c.id === cid)) {
      showToast("该频道 ID 已存在", "err");
      return;
    }
    state.channels.push({ name, id: cid });
    nameEl.value = "";
    idEl.value = "";
    renderChannels();
  }

  function restoreDefaultChannels() {
    state.channels = state.defaultChannels.map((c) => ({ ...c }));
    renderChannels();
    showToast("已恢复默认频道（点击保存设置生效）", "info");
  }

  async function saveSettings() {
    const scanIv = parseFloat(document.getElementById("set-scan-iv").value);
    const tokIv = parseFloat(document.getElementById("set-token-iv").value);
    const port = parseInt(document.getElementById("set-web-port").value, 10);

    if (!(scanIv > 0) || !(tokIv > 0)) {
      showToast("扫描与刷新间隔必须大于 0", "err");
      return;
    }
    if (!(port >= 1 && port <= 65535)) {
      showToast("Web 面板端口号必须在 1 ~ 65535 之间", "err");
      return;
    }
    if (state.channels.length === 0) {
      showToast("请至少保留一个监控频道", "err");
      return;
    }

    const payload = {
      scan_interval_hours: scanIv,
      token_refresh_interval_hours: tokIv,
      auto_check_monthly_total: document.getElementById("set-auto-check").checked,
      disable_system_proxy: document.getElementById("set-disable-proxy").checked,
      multi_account_auto: document.getElementById("set-multi-auto").checked,
      channels: state.channels,
      web_panel: {
        enabled: document.getElementById("set-web-enabled").checked,
        allow_lan: document.getElementById("set-web-lan").checked,
        port,
        access_pin: document.getElementById("set-web-pin").value.trim(),
      },
    };

    try {
      const res = await apiPost("/api/settings", payload);
      const d = await res.json();
      showToast(d.msg || "设置已保存", d.ok ? "ok" : "err");
      fetchStatus();
    } catch (e) {
      if (e.message !== "AUTH_REQUIRED") showToast("保存设置失败", "err");
    }
  }

  // ==================== 结构化日志解析与增量渲染 ====================
  const EMOJI_PREFIX_RE = /^(?:\p{Extended_Pictographic}|[\u2600-\u27BF\uFE0F\u200D\s])+/u;

  function parseLogLine(raw) {
    let time = "";
    let level = "INFO";
    let body = raw;

    const m = raw.match(/^\d{4}-\d{2}-\d{2}\s+(\d{2}:\d{2}:\d{2})\s+-\s+\[(\w+)\]\s+-\s+(.*)$/);
    if (m) {
      time = m[1];
      level = m[2];
      body = m[3];
    }

    let cleanBody = body.replace(EMOJI_PREFIX_RE, "").trim() || body.trim();
    if (/^={5,}$/.test(cleanBody)) {
      cleanBody = "────────────────────────";
    }
    let kind = "info";
    let tag = "信息";

    if (level === "ERROR" || body.includes("❌")) {
      kind = "err";
      tag = "错误";
    } else if (level === "WARNING" || body.includes("⚠️")) {
      kind = "warn";
      tag = "警告";
    } else if (/^─{3,}$/.test(cleanBody) || /─{3,}/.test(cleanBody)) {
      kind = "hdr";
      tag = "系统";
    } else if (
      body.includes("本地已记录，跳过") ||
      body.includes("服务端已赞，跳过") ||
      body.includes("已点赞（服务端确认）")
    ) {
      kind = "skip";
      tag = "跳过";
    } else if (
      body.includes("点赞成功") ||
      body.includes("🎉") ||
      (body.includes("✅") && !body.includes("环境检查"))
    ) {
      kind = "liked";
      tag = body.includes("完成") ? "完成" : "已赞";
    }

    return { raw, time, kind, tag, text: cleanBody };
  }

  function matchLog(entry) {
    if (state.logFilter === "liked" && entry.kind !== "liked") return false;
    if (state.logFilter === "warn" && entry.kind !== "warn" && entry.kind !== "err") return false;
    if (state.logQuery) {
      const q = state.logQuery.toLowerCase();
      if (!entry.raw.toLowerCase().includes(q)) return false;
    }
    return true;
  }

  function buildLogNode(entry) {
    const row = document.createElement("div");
    row.className = `log-row is-${entry.kind}`;
    if (entry.time) {
      const t = document.createElement("span");
      t.className = "log-time";
      t.textContent = entry.time;
      row.appendChild(t);
    }
    const badge = document.createElement("span");
    badge.className = `log-tag ${entry.kind}`;
    badge.textContent = entry.tag;
    const msg = document.createElement("span");
    msg.className = "log-msg";
    msg.textContent = entry.text;
    row.append(badge, msg);
    return row;
  }

  function rerenderLogs() {
    const box = document.getElementById("log-body");
    const frag = document.createDocumentFragment();
    let count = 0;
    for (const entry of state.rawLogs) {
      if (matchLog(entry)) {
        frag.appendChild(buildLogNode(entry));
        count += 1;
      }
    }
    if (count === 0) {
      box.innerHTML = '<div class="log-empty">暂无符合条件的运行记录</div>';
    } else {
      box.replaceChildren(frag);
      if (state.autoScroll) box.scrollTop = box.scrollHeight;
    }
  }

  function setLogFilter(mode, btn) {
    state.logFilter = mode;
    document.querySelectorAll("#log-filter-group .filter-pill").forEach((b) => {
      b.classList.toggle("active", b === btn);
    });
    rerenderLogs();
  }

  function setLogQuery(val) {
    state.logQuery = val.trim();
    rerenderLogs();
  }

  function toggleAutoScroll() {
    state.autoScroll = !state.autoScroll;
    document.getElementById("btn-autoscroll").classList.toggle("active", state.autoScroll);
    if (state.autoScroll) {
      const box = document.getElementById("log-body");
      box.scrollTop = box.scrollHeight;
    }
  }

  function copyLogs() {
    const lines = state.rawLogs.filter(matchLog).map((e) => e.raw);
    if (!lines.length) {
      showToast("当前视图没有可复制的日志", "info");
      return;
    }
    copyToClipboard(lines.join("\n"), "当前视图日志已复制");
  }

  async function openLogFile() {
    if (isDesktop() && window.pywebview.api.open_log_file) {
      const res = await window.pywebview.api.open_log_file();
      if (!res?.ok) showToast(res?.msg || "无法打开日志文件", "err");
    }
  }

  function clearLogsView() {
    state.rawLogs = [];
    rerenderLogs();
  }

  // ==================== 远程与辅助动作 ====================
  async function openRemote() {
    const url = state.snapshot?.web_info?.url || state.snapshot?.web_info?.lan_url;
    if (!url) return;
    if (isDesktop() && window.pywebview.api.open_external_url) {
      await window.pywebview.api.open_external_url(url);
    } else {
      window.open(url, "_blank");
    }
  }

  function copyRemote() {
    const url = state.snapshot?.web_info?.lan_url || state.snapshot?.web_info?.url;
    if (url) copyToClipboard(url, `已复制远程地址: ${url}`);
  }

  function togglePassword(inputId, btn) {
    const input = document.getElementById(inputId);
    if (!input) return;
    const show = input.type === "password";
    input.type = show ? "text" : "password";
    const useEl = btn.querySelector("use");
    if (useEl) useEl.setAttribute("href", show ? "#i-eye-off" : "#i-eye");
  }

  function askConfirm(title, desc) {
    return new Promise((resolve) => {
      state.confirmResolver = resolve;
      document.getElementById("confirm-title").textContent = title;
      document.getElementById("confirm-desc").textContent = desc;
      document.getElementById("confirm-overlay").classList.add("show");
    });
  }

  function resolveConfirm(val) {
    document.getElementById("confirm-overlay").classList.remove("show");
    if (state.confirmResolver) {
      state.confirmResolver(val);
      state.confirmResolver = null;
    }
  }

  function showPinOverlay(show) {
    const el = document.getElementById("pin-overlay");
    el.classList.toggle("show", show);
    if (show) setTimeout(() => document.getElementById("pin-input").focus(), 60);
  }

  async function submitPin(ev) {
    ev.preventDefault();
    const pin = document.getElementById("pin-input").value.trim();
    const errEl = document.getElementById("pin-err");
    errEl.textContent = "";
    try {
      const res = await fetch("/api/auth", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ pin }),
      });
      const d = await res.json();
      if (res.ok && d.ok) {
        document.getElementById("pin-input").value = "";
        showPinOverlay(false);
        state.logCursor = null;
        fetchStatus();
        fetchLogs();
      } else {
        errEl.textContent = d.msg || "口令错误";
      }
    } catch (_) {
      errEl.textContent = "验证请求失败";
    }
  }

  function escapeHtml(s) {
    return String(s || "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  // ==================== 轮询同步 ====================
  async function fetchStatus() {
    try {
      const res = await apiGet("/api/status");
      const snap = await res.json();
      renderSnapshot(snap);
    } catch (_) {}
  }

  async function fetchLogs() {
    try {
      const q = state.logCursor === null ? "" : `?cursor=${state.logCursor}`;
      const res = await apiGet(`/api/logs${q}`);
      const d = await res.json();
      const box = document.getElementById("log-body");

      if (d.reset) {
        state.rawLogs = [];
        box.innerHTML = "";
      }

      const lines = d.lines || [];
      if (lines.length > 0) {
        if (box.querySelector(".log-empty")) {
          box.innerHTML = "";
        }
        const frag = document.createDocumentFragment();
        for (const raw of lines) {
          if (!raw.trim()) continue;
          const entry = parseLogLine(raw);
          state.rawLogs.push(entry);
          if (matchLog(entry)) {
            frag.appendChild(buildLogNode(entry));
          }
        }
        while (state.rawLogs.length > MAX_LOGS) {
          state.rawLogs.shift();
        }
        if (frag.childNodes.length > 0) {
          box.appendChild(frag);
          while (box.childElementCount > MAX_LOGS) {
            box.removeChild(box.firstElementChild);
          }
          if (state.autoScroll) {
            box.scrollTop = box.scrollHeight;
          }
        } else if (box.childElementCount === 0) {
          box.innerHTML = '<div class="log-empty">暂无符合条件的运行记录</div>';
        }
      } else if (state.rawLogs.length === 0 && !box.querySelector(".log-empty")) {
        box.innerHTML = '<div class="log-empty">暂无运行动态</div>';
      }

      if (typeof d.cursor === "number") {
        state.logCursor = d.cursor;
      }
    } catch (_) {}
  }

  function init() {
    applyThemeVisual("System");
    initPresets();
    fetchStatus();
    fetchLogs();

    // 空闲时温和轮询，任务运行中自动加密刷新进度
    let tick = 0;
    setInterval(() => {
      tick += 1;
      const busy = Boolean(state.snapshot && (state.snapshot.is_scanning || state.snapshot.is_exporting));
      if (busy || tick % 2 === 0) {
        fetchStatus();
      }
    }, 1000);

    setInterval(fetchLogs, 1500);
  }

  document.addEventListener("DOMContentLoaded", init);

  return {
    switchView,
    toggleAccountPopover,
    closeAccountPopover,
    setTheme,
    startScan,
    toggleAuto,
    stopTask,
    refreshToken,
    checkToken,
    exportReport,
    openOrDownloadReport,
    openRemote,
    copyRemote,
    setLogFilter,
    setLogQuery,
    toggleAutoScroll,
    copyLogs,
    openLogFile,
    clearLogsView,
    submitAccountForm,
    submitOnboarding,
    togglePassword,
    addChannel,
    restoreDefaultChannels,
    saveSettings,
    resolveConfirm,
    submitPin,
  };
})();
