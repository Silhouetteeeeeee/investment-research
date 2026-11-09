const { createApp } = Vue;

const WELCOME_EXAMPLES = [
  "紫金矿业靠什么赚钱？",
  "东鹏饮料毛利率为什么下滑？",
  "今天大盘怎么看？",
];
const STORAGE_KEY = "front_conversation";
const SPLIT_KEY = "front_split_conversation";
const RATIO_KEY = "front_split_ratio";
const MAX_SOURCE_ROWS = 6;
const MIN_RATIO = 22;
const MAX_RATIO = 78;
const TOKEN_KEY = "ir_token";

// 访问令牌只存在浏览器本地，随请求头发送；401 时问一次并重试一次，不再循环
function askToken() {
  const entered = window.prompt("请输入访问令牌");
  if (!entered) return null;
  const token = entered.trim();
  if (!token) return null;
  localStorage.setItem(TOKEN_KEY, token);
  return token;
}

async function apiFetch(url, options = {}) {
  const opts = { ...options, headers: { ...(options.headers || {}) } };
  const sent = localStorage.getItem(TOKEN_KEY);
  if (sent) opts.headers.Authorization = `Bearer ${sent}`;
  let response = await fetch(url, opts);
  if (response.status === 401) {
    // 并行请求会同时 401；先看别的请求是否已经问到并保存了新的令牌，避免连续弹两次
    const stored = localStorage.getItem(TOKEN_KEY);
    const fresh = stored && stored !== sent ? stored : askToken();
    if (fresh) {
      opts.headers.Authorization = `Bearer ${fresh}`;
      response = await fetch(url, opts);
    }
  }
  return response;
}

function escapeAttr(value) {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/"/g, "&quot;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

// 联网搜索的来源 id 以 web: 开头（WebSearchAdapter 的约定），用来和库内结构化来源区分
function isWebSource(source) {
  return String((source && source.id) || "").startsWith("web:");
}

function renderBody(text, sources, streaming) {
  const map = new Map();
  (sources || []).forEach((source) => {
    const id = String(source.id || "");
    if (id && !map.has(id)) map.set(id, { n: map.size + 1, source });
  });

  let src = String(text || "");
  if (streaming) {
    src = src.replace(/\[\[[^\]]*\]\]/g, "").replace(/\[\[[^\]]*$/, "");
  } else {
    src = src.replace(/\[\[([^\[\]]+)\]\]/g, (_, rawId) => {
      const hit = map.get(rawId.trim());
      const source = (hit && hit.source) || {};
      const number = hit ? hit.n : "?";
      const label = source.label || source.id || (hit ? "未知来源" : "来源未登记");
      const attrs = [
        `data-id="${escapeAttr(source.id || "")}"`,
        `data-label="${escapeAttr(label)}"`,
        `data-detail="${escapeAttr(source.detail || "")}"`,
        `data-asof="${escapeAttr(source.as_of || "")}"`,
      ];
      if (source.url) attrs.push(`data-url="${escapeAttr(source.url)}"`);
      const cls = isWebSource(source) ? "cite web" : "cite";
      return `<sup class="${cls}" ${attrs.join(" ")}>[${number}]</sup>`;
    });
  }
  const html = marked.parse(src, { gfm: true, breaks: true });
  return DOMPurify.sanitize(html, {
    ADD_ATTR: ["data-id", "data-label", "data-detail", "data-asof", "data-url"],
  });
}

function decorate(message) {
  return { ...message, html: renderBody(message.text || "", message.sources || [], false) };
}

// 工具耗时：一秒以内按毫秒，超过按秒；旧记录没有这个字段，按 0 显示
function formatDuration(value) {
  const ms = Math.max(0, Math.round(Number(value) || 0));
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`;
}

function formatTokens(value) {
  const n = Number(value) || 0;
  if (n >= 10000) return `${(n / 10000).toFixed(1)}万`;
  if (n >= 1000) return `${(n / 1000).toFixed(1)}k`;
  return String(Math.round(n));
}

function fileSize(bytes) {
  const n = Math.max(0, Math.round(Number(bytes) || 0));
  if (n >= 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)}MB`;
  if (n >= 1024) return `${(n / 1024).toFixed(0)}KB`;
  return `${n}B`;
}

// 阶段 payload 里的 raw/run 原样展开，交易页不允许丢内容
function prettyJson(value) {
  if (value === null || value === undefined) return "（没有记录）";
  try {
    return JSON.stringify(value, null, 2);
  } catch (error) {
    return String(value);
  }
}

const STOP_LABELS = {
  answered: "已回答",
  rounds_exhausted: "轮次用尽",
  error: "出错",
};

// 时间线里的绝对位置：不足一秒按毫秒，之后按秒
function formatOffset(value) {
  const ms = Math.max(0, Math.round(Number(value) || 0));
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`;
}

function percentText(share) {
  const value = Number(share);
  // JDK 在拿不到 CPU 采样时会返回 -1
  if (!Number.isFinite(value) || value < 0) return "—";
  return `${Math.round(value * 100)}%`;
}

function shortDate(value) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  const now = new Date();
  const hh = String(date.getHours()).padStart(2, "0");
  const mm = String(date.getMinutes()).padStart(2, "0");
  if (date.toDateString() === now.toDateString()) return `今天 ${hh}:${mm}`;
  const sameYear = date.getFullYear() === now.getFullYear();
  const md = `${date.getMonth() + 1}-${date.getDate()}`;
  return sameYear ? md : `${date.getFullYear()}-${md}`;
}

// 依据里常见的字段名，翻译成人话；没登记的原样显示
const EVIDENCE_LABELS = {
  code: "代码", name: "名称", company: "公司", industry: "行业", profile: "简介",
  period: "报告期", as_of: "数据日期", report: "报表", qdate: "报告季度",
  datatype: "报表类型", revenue_yi: "营业收入(亿)", revenue_yoy: "营收同比(%)",
  revenue_sq_yi: "单季营收(亿)", revenue_sq_yoy: "单季营收同比(%)",
  net_profit_yi: "净利润(亿)", net_profit_yoy: "净利润同比(%)",
  net_profit_sq_yi: "单季净利润(亿)", net_profit_sq_yoy: "单季净利润同比(%)",
  gross_margin: "毛利率(%)", gross_margin_sq: "单季毛利率(%)", net_margin: "净利率(%)",
  roe: "ROE(%)", roa: "ROA(%)", debt_ratio: "资产负债率(%)",
  pe_ttm: "市盈率TTM", pb_mrq: "市净率MRQ", price: "股价", market_cap_yi: "总市值(亿)",
  pe_percentile: "PE分位(%)", pb_percentile: "PB分位(%)", years: "年数", span: "区间",
  income_yi: "收入(亿)", share: "占比(%)", ratio: "比例(%)", rank: "排名",
  date: "日期", title: "标题", tags: "标签", level: "级别", latest: "最新",
  events: "事件", count: "数量", groups: "分组", items: "明细", slices: "构成",
  total_income_yi: "合计收入(亿)", kind: "类别", label: "类别", controller: "实控人",
  top10: "前十大股东", has_dividends: "有分红", has_buybacks: "有回购",
  has_reports: "有研报", has_forecasts: "有预告", has_pledges: "有质押",
  has_lockups: "有解禁", has_trades: "有增减持", has_research: "有调研",
  plan: "方案", report_date: "报告期", fiscal_year: "年度", period_label: "报告类型",
  dividend_yield: "股息率(%)", payout_ratio: "分红率(%)", amount_yi: "金额(亿)",
  revenue: "营收", net_profit: "净利润", eps: "EPS", bps: "BPS", cash: "现金",
  stale: "缓存偏旧", cached: "复用缓存", mode: "模式", valuation: "估值",
  financials: "财务", scenarios: "情景", assumptions: "假设", base_value: "基准值",
  base_label: "基准", growth_pct: "增速假设(%)", exit_pe: "退出PE",
  salary_total_wan: "薪酬合计(万)", window_start: "窗口开始", window_end: "窗口结束",
  days: "天数", has_related: "有相关交易", segments: "业务构成", index: "指数",
  close: "收盘", change_pct: "涨跌幅(%)", amount: "数量", value: "值",
};

function evidenceText(value) {
  if (value === null || value === undefined) return "--";
  if (typeof value === "number") {
    if (!Number.isFinite(value)) return String(value);
    return Number.isInteger(value) ? String(value) : String(Math.round(value * 100) / 100);
  }
  if (typeof value === "boolean") return value ? "是" : "否";
  return String(value);
}

// 依据数据：值、对象、对象数组分别显示成字段行、嵌套块和表格
const EvidenceView = {
  name: "EvidenceView",
  props: {
    value: { default: null },
    depth: { type: Number, default: 0 },
  },
  computed: {
    isObject() {
      return !!this.value && typeof this.value === "object" && !Array.isArray(this.value);
    },
    isArray() {
      return Array.isArray(this.value);
    },
    entries() {
      if (!this.isObject) return [];
      return Object.entries(this.value)
        .filter(([, value]) => {
          if (value === null || value === undefined || value === "") return false;
          return !(Array.isArray(value) && !value.length);
        })
        .map(([key, value]) => ({ key, value }));
    },
    rows() {
      return this.isArray ? this.value : [];
    },
    tableHeaders() {
      if (!this.isArray) return [];
      const keys = [];
      this.value.forEach((row) => {
        if (!row || typeof row !== "object" || Array.isArray(row)) return;
        Object.keys(row).forEach((key) => {
          if (!keys.includes(key)) keys.push(key);
        });
      });
      return keys;
    },
    isTable() {
      if (!this.isArray || !this.value.length || !this.tableHeaders.length) return false;
      return this.value.every((row) =>
        row && typeof row === "object" && !Array.isArray(row) &&
        this.tableHeaders.every((key) => row[key] === null || typeof row[key] !== "object")
      );
    },
  },
  methods: {
    label(key) {
      return EVIDENCE_LABELS[key] || key;
    },
    text: evidenceText,
    isComplex(value) {
      return value !== null && typeof value === "object";
    },
    summary(value) {
      if (Array.isArray(value)) return `${value.length} 项`;
      return `${Object.keys(value || {}).length} 个字段`;
    },
    cell(row, key) {
      return evidenceText(row[key]);
    },
  },
  template: `
    <div class="ev">
      <template v-if="isObject">
        <div class="ev-row" v-for="entry in entries" :key="entry.key">
          <span class="ev-key">{{ label(entry.key) }}</span>
          <details class="ev-more" v-if="isComplex(entry.value)" :open="depth === 0">
            <summary>{{ summary(entry.value) }}</summary>
            <evidence-view :value="entry.value" :depth="depth + 1"></evidence-view>
          </details>
          <span class="ev-val" v-else>{{ text(entry.value) }}</span>
        </div>
      </template>
      <div class="ev-table-wrap" v-else-if="isTable">
        <table class="ev-table">
          <thead>
            <tr><th v-for="key in tableHeaders" :key="key">{{ label(key) }}</th></tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in rows" :key="index">
              <td v-for="key in tableHeaders" :key="key">{{ cell(row, key) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <template v-else-if="isArray">
        <div class="ev-item" v-for="(item, index) in value" :key="index">
          <evidence-view :value="item" :depth="depth + 1"></evidence-view>
        </div>
      </template>
      <span class="ev-val" v-else>{{ text(value) }}</span>
    </div>
  `,
};

// ---------------------------------------------------------------------------
// 一个对话栏：会话、消息、流式回答、输入框全部自己持有。
// 两栏各自是独立的滚动容器和请求，互不等待，也互不撑高。
// ---------------------------------------------------------------------------
const ChatPane = {
  name: "ChatPane",
  template: "#pane-template",
  props: {
    secondary: { type: Boolean, default: false },
    archiveCodes: { type: Array, default: () => [] },
  },
  emits: ["state", "changed", "close", "open-source", "close-source", "open-archive"],
  data() {
    return {
      cid: null,
      activeName: "",
      activeCode: "",
      messages: [],
      examples: WELCOME_EXAMPLES,
      notice: "",
      input: "",
      busy: false,
      phase: "idle",
      streamText: "",
      streamEmpty: "正在思考…",
      statusLine: "",
      usage: null,
      copied: -1,
      tip: null,
      runOpen: -1,
      runTab: "timeline",
      runTabs: [
        { key: "timeline", label: "时间线" },
        { key: "prompt", label: "Prompt" },
        { key: "context", label: "上下文" },
      ],
      run: null,
      runState: "idle",
      runError: "",
    };
  },
  computed: {
    streamHtml() {
      return renderBody(this.streamText, [], true);
    },
    meterStyle() {
      const current = this.usage || {};
      const ratio = Math.max(0, Math.min(1, Number(current.ratio) || 0));
      const color = current.level === "over" ? "#e5655c" : current.level === "warn" ? "#e8b96b" : "#5b8cff";
      return { "--pct": String(ratio * 100), "--color": color };
    },
    meterText() {
      const percent = Number((this.usage || {}).percent) || 0;
      return percent >= 1000 ? "999+" : `${Math.round(percent)}%`;
    },
    meterTitle() {
      const current = this.usage || {};
      if (!current.tokens) return "上下文占用";
      return `上下文 ${formatTokens(current.tokens)} / ${formatTokens(current.budget_tokens)} tokens · ${Math.round(Number(current.percent) || 0)}%`;
    },
    runHead() {
      const run = this.run;
      if (!run) return [];
      const models = (run.stages || []).filter((stage) => stage.kind === "model");
      const rounds = Number(run.rounds) || models.length;
      const max = Number(run.max_rounds) || 0;
      return [
        { value: formatDuration(run.total_ms), label: "总耗时" },
        { value: max ? `${rounds} / ${max} 轮` : `${rounds} 轮`, label: "模型轮次" },
        {
          value: STOP_LABELS[run.stop_reason] || run.stop_reason || "未知",
          label: "停止原因",
          bad: run.stop_reason !== "answered",
        },
        { value: `${models.length} 次`, label: "模型调用" },
      ];
    },
    runTimeline() {
      const run = this.run;
      if (!run) return [];
      const rows = (run.stages || []).map((stage) => this.stageRow(stage));
      if (run.error) {
        rows.push({
          at: "—", duration: "—", title: "运行失败", meta: run.error, bad: true,
        });
      }
      return rows;
    },
    runContext() {
      const run = this.run;
      if (!run) return [];
      const rows = [];
      (run.stages || []).forEach((stage) => {
        if (stage.kind === "context_prepare") {
          rows.push({
            label: "上下文准备",
            value: `${formatTokens(stage.before_tokens)} → ${formatTokens(stage.after_tokens)} tokens · 丢弃 ${stage.dropped_tool_groups || 0} 组工具结果 · 摘要 ${stage.summarized_turns || 0} 轮`,
          });
        } else if (stage.kind === "model") {
          const source = stage.usage_source === "estimate" ? "估算" : "API 统计";
          const parts = [
            `消息 ${stage.message_count || 0} 条`,
            `prompt ${formatTokens(stage.prompt_tokens)} tokens（${source}）`,
            `system ${percentText(stage.system_share)}`,
            `工具 ${percentText(stage.tool_share)}`,
          ];
          if (stage.input_chars) parts.push(`输入 ${stage.input_chars} 字`);
          rows.push({ label: `第 ${stage.round} 轮`, value: parts.join(" · ") });
        }
      });
      return rows;
    },
  },
  created() {
    this.noticeTimer = null;
    this.hideTimer = null;
    this.lastStreamScroll = 0;
    this.tipAnchor = null;
  },
  beforeUnmount() {
    if (this.noticeTimer) clearTimeout(this.noticeTimer);
    if (this.hideTimer) clearTimeout(this.hideTimer);
  },
  methods: {
    formatTokens,
    fileSize,
    notify() {
      this.$emit("state", { id: this.cid, name: this.activeName, code: this.activeCode });
    },

    // ---------------- 会话 ----------------
    async open(id) {
      if (this.busy) return;
      if (!id) {
        this.reset();
        return;
      }
      if (this.cid === id) return;
      try {
        const response = await apiFetch(`/api/conversations/${id}`);
        if (!response.ok) {
          this.reset();
          return;
        }
        const data = await response.json();
        this.cid = data.id;
        this.activeName = data.name || "";
        this.activeCode = data.code || "";
        this.messages = (data.messages || []).map(decorate);
        this.usage = data.usage || null;
        this.phase = "idle";
        this.streamText = "";
        this.notice = "";
        this.copied = -1;
        this.closeRun();
        this.hideTip();
        this.$emit("close-source");
        this.scroll(true);
        this.notify();
      } catch (error) {
        this.reset();
      }
    },
    reset() {
      this.cid = null;
      this.activeName = "";
      this.activeCode = "";
      this.messages = [];
      this.usage = null;
      this.phase = "idle";
      this.streamText = "";
      this.notice = "";
      this.copied = -1;
      this.closeRun();
      this.hideTip();
      this.$emit("close-source");
      this.scroll(true);
      this.notify();
    },
    newChat() {
      this.reset();
      this.$nextTick(() => this.focusInput());
    },
    async deleteConversation() {
      const id = this.cid;
      if (id === null) return;
      try {
        await apiFetch(`/api/conversations/${id}/delete`, { method: "POST" });
      } catch (error) { /* 删不掉也先清页面 */ }
      this.reset();
      this.$emit("changed");
      if (this.secondary) this.$emit("close");
    },

    // ---------------- 提问与流式 ----------------
    async send() {
      const question = this.input.trim();
      if (!question || this.busy) return;

      const userMessage = { role: "user", text: question };
      this.messages.push(userMessage);
      this.input = "";
      this.busy = true;
      this.phase = "streaming";
      this.streamText = "";
      this.streamEmpty = "正在思考…";
      this.statusLine = "";
      this.notice = "";
      this.copied = -1;
      this.hideTip();
      this.$emit("close-source");
      if (this.$refs.inputEl) this.$refs.inputEl.style.height = "auto";
      this.scroll(true);

      try {
        const response = await apiFetch("/api/ask", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ conversation_id: this.cid, question }),
        });
        if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}`);

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";

        while (true) {
          const chunk = await reader.read();
          if (chunk.done) break;
          buffer += decoder.decode(chunk.value, { stream: true });

          let split;
          while ((split = buffer.indexOf("\n\n")) >= 0) {
            const frame = buffer.slice(0, split);
            buffer = buffer.slice(split + 2);
            const line = frame.split("\n").find((item) => item.startsWith("data:"));
            if (!line) continue;
            let event;
            try {
              event = JSON.parse(line.slice(5).trim());
            } catch (error) {
              continue;
            }

            if (event.type === "state") {
              const previous = this.cid;
              if (event.conversation_id && event.conversation_id !== this.cid) {
                this.cid = event.conversation_id;
                // 换公司时服务端开了新会话：旧记录清掉，只留这一问
                if (previous !== null) this.messages = [userMessage];
                this.$emit("changed");
              }
              this.activeName = event.name || "";
              this.activeCode = event.code || "";
              this.notify();
              if (event.notice) this.setNotice(event.notice);
            } else if (event.type === "text") {
              this.streamText += event.delta || "";
              this.streamScroll();
            } else if (event.type === "discard") {
              this.streamText = "";
              this.streamEmpty = "正在取数…";
              this.statusLine = "";
            } else if (event.type === "tool") {
              const outcome = event.cached ? "复用缓存" : event.ok ? "成功" : "失败";
              this.statusLine = `已取数 · ${event.name || "工具"} · ${event.company || "--"} · ${outcome} · ${formatDuration(event.duration_ms)}`;
            } else if (event.type === "meter") {
              this.usage = event.usage || null;
            } else if (event.type === "warning") {
              this.messages.push({ role: "assistant", text: event.text, warning: true });
              this.phase = "idle";
            } else if (event.type === "error") {
              this.messages.push({ role: "error", text: event.text, run: event.run || null });
              this.phase = "idle";
              if (event.usage) this.usage = event.usage;
            } else if (event.type === "done") {
              this.messages.push(decorate(event.message));
              this.phase = "idle";
              this.streamText = "";
              this.statusLine = "";
              if (event.usage) this.usage = event.usage;
              this.$emit("changed");
              this.notify();
              this.scroll(true);
            }
          }
        }
      } catch (error) {
        this.messages.push({ role: "error", text: `连接失败：${error.message || error}` });
      } finally {
        this.busy = false;
        this.phase = "idle";
        this.streamText = "";
        this.$nextTick(() => this.focusInput());
      }
    },

    // ---------------- 输入框 ----------------
    useExample(text) {
      this.input = text;
      this.$nextTick(() => {
        this.focusInput();
        const element = this.$refs.inputEl;
        if (!element) return;
        element.style.height = "auto";
        element.style.height = `${Math.min(element.scrollHeight, 168)}px`;
      });
    },
    focusInput() {
      if (this.$refs.inputEl) this.$refs.inputEl.focus();
    },
    grow(event) {
      const element = event.target;
      element.style.height = "auto";
      element.style.height = `${Math.min(element.scrollHeight, 168)}px`;
    },
    onKeydown(event) {
      if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
        event.preventDefault();
        this.send();
      }
    },

    // ---------------- 展示 ----------------
    setNotice(text) {
      this.notice = text || "";
      if (this.noticeTimer) clearTimeout(this.noticeTimer);
      if (text) {
        this.noticeTimer = setTimeout(() => { this.notice = ""; }, 8000);
      }
    },
    scroll(force = false) {
      this.$nextTick(() => {
        const box = this.$refs.scrollEl;
        if (!box) return;
        const nearBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 180;
        if (force || nearBottom) box.scrollTop = box.scrollHeight;
      });
    },
    streamScroll() {
      const now = Date.now();
      if (now - this.lastStreamScroll < 120) return;
      this.lastStreamScroll = now;
      this.scroll(false);
    },
    sourceRows(message) {
      return (message.sources || []).slice(0, MAX_SOURCE_ROWS).map((source, index) => ({
        n: index + 1,
        index,
        label: source.label || source.id || "未知来源",
        detail: source.detail || "",
        asof: source.as_of || "",
        url: source.url || "",
        web: isWebSource(source),
      }));
    },
    // 来源详情放在页面最右侧的全局侧栏里，这里只负责把点击转给页面
    requestSource(message, index) {
      this.hideTip();
      this.$emit("open-source", message, index, this.cid);
    },
    traceOf(message) {
      const steps = Array.isArray(message.trace) ? message.trace : [];
      const count = steps.length || message.trace_count || 0;
      const lines = steps.map((step) => {
        const outcome = step.ok === false ? `失败：${step.error || "未知错误"}` : "成功";
        return `${step.round || "-"} · ${step.name || "未知工具"} · ${step.company || "--"} · ${step.as_of || "无日期"} · ${outcome} · 耗时 ${formatDuration(step.duration_ms)}`;
      });
      return { count, lines };
    },

    // ---------------- 运行详情 ----------------
    stageRow(stage) {
      const base = {
        at: formatOffset(stage.start_ms),
        duration: formatDuration(stage.duration_ms),
        nested: false,
        bad: false,
      };
      if (stage.kind === "context_prepare") {
        return {
          ...base,
          title: "上下文准备",
          meta: `${formatTokens(stage.before_tokens)} → ${formatTokens(stage.after_tokens)} tokens · 丢弃 ${stage.dropped_tool_groups || 0} 组工具结果 · 摘要 ${stage.summarized_turns || 0} 轮`,
        };
      }
      if (stage.kind === "summary") {
        return {
          ...base,
          nested: true,
          title: "上下文摘要",
          meta: `${stage.prompt_tokens || 0} → ${stage.completion_tokens || 0} tokens · 摘要 ${stage.summary_chars || 0} 字${stage.usage_source === "estimate" ? " · 估算" : ""}`,
        };
      }
      if (stage.kind === "context_trim") {
        return {
          ...base,
          title: "上下文裁剪",
          meta: `${formatTokens(stage.before_tokens)} → ${formatTokens(stage.after_tokens)} tokens`,
        };
      }
      if (stage.kind === "model") {
        const ttft = stage.ttft_ms === null || stage.ttft_ms === undefined
          ? "首 token —"
          : `首 token ${formatDuration(stage.ttft_ms)}`;
        const parts = [
          ttft,
          `输入 ${formatTokens(stage.prompt_tokens)} / 输出 ${formatTokens(stage.completion_tokens)}`,
          stage.finish_reason || "结束原因未知",
        ];
        if (stage.stream) parts.push("流式");
        if (stage.usage_source === "estimate") parts.push("估算");
        if (stage.forced) parts.push("禁工具补答");
        const title = stage.forced
          ? `第 ${stage.round} 轮模型调用 · 轮次用尽后作答`
          : `第 ${stage.round} 轮模型调用`;
        return { ...base, title, meta: parts.join(" · ") };
      }
      if (stage.kind === "tool") {
        const outcome = stage.ok
          ? (stage.cached ? "复用缓存" : "成功")
          : `失败：${stage.error || "未知错误"}`;
        const sources = stage.sources || [];
        return {
          ...base,
          bad: stage.ok === false,
          title: `工具 · ${stage.name || "未知工具"}`,
          meta: `${stage.company || "--"} · ${stage.as_of || "无日期"} · ${outcome} · ${stage.data_chars || 0} 字符 · ${sources.length ? sources.join("、") : "无来源"}`,
        };
      }
      return { ...base, title: stage.kind || "未知阶段", meta: "" };
    },
    messagesText(value) {
      try {
        return JSON.stringify(value, null, 2);
      } catch (error) {
        return String(value === undefined ? "" : value);
      }
    },
    paramsText(params) {
      if (!params || typeof params !== "object") return "无参数";
      return Object.entries(params)
        .map(([key, value]) => `${key}=${typeof value === "string" ? value : JSON.stringify(value)}`)
        .join(" · ");
    },
    closeRun() {
      this.runOpen = -1;
      this.runTab = "timeline";
      this.run = null;
      this.runState = "idle";
      this.runError = "";
    },
    async toggleRun(message, index) {
      if (this.runOpen === index) {
        this.runOpen = -1;
        return;
      }
      this.runOpen = index;
      this.runTab = "timeline";
      this.run = null;
      this.runError = "";
      const runId = message.run && message.run.id;
      if (!this.cid || !runId) {
        this.runState = "error";
        this.runError = "这条消息没有运行记录。";
        return;
      }
      this.runState = "loading";
      try {
        const response = await apiFetch(`/api/conversations/${this.cid}/runs/${encodeURIComponent(runId)}`);
        if (!response.ok) throw new Error(String(response.status));
        const data = await response.json();
        if (this.runOpen !== index) return;
        this.run = data;
        this.runState = "ready";
      } catch (error) {
        if (this.runOpen !== index) return;
        this.runState = "error";
        this.runError = "运行原文不存在或已被清理（只保留最近 200 次运行）。";
      }
    },
    async copyMessage(event, index) {
      const messageEl = event.currentTarget.closest(".msg");
      if (!messageEl) return;
      const body = messageEl.querySelector(".body");
      const rows = [...messageEl.querySelectorAll(".source-row, .src-note")].map((row) => row.innerText.trim());
      const parts = [];
      if (body) parts.push(body.innerText.trim());
      if (rows.length) parts.push(`来源：\n${rows.join("\n")}`);
      try {
        await navigator.clipboard.writeText(parts.join("\n\n"));
        this.copied = index;
        setTimeout(() => { if (this.copied === index) this.copied = -1; }, 1500);
      } catch (error) { /* 剪贴板不可用时不动 */ }
    },

    // ---------------- 引用悬停卡 ----------------
    onCiteClick(event) {
      const target = event.target;
      const cite = target && target.closest ? target.closest("sup.cite") : null;
      if (!cite) return;
      const messageEl = cite.closest(".msg");
      if (!messageEl) return;
      const message = this.messages[Number(messageEl.dataset.msgIndex)];
      if (!message || !message.sources) return;
      const id = cite.dataset.id || "";
      const index = message.sources.findIndex((source) => String(source.id || "") === id);
      if (index < 0) return;
      const source = message.sources[index];
      // 网页来源点角标直接开原文；要看完整来源信息仍可点下方来源行
      if (isWebSource(source) && source.url) {
        window.open(source.url, "_blank", "noopener,noreferrer");
        return;
      }
      this.requestSource(message, index);
    },
    showTip(event) {
      const target = event.target;
      const cite = target && target.closest ? target.closest("sup.cite") : null;
      if (!cite) return;
      if (this.hideTimer) { clearTimeout(this.hideTimer); this.hideTimer = null; }
      this.tipAnchor = cite.getBoundingClientRect();
      this.tip = {
        n: (cite.textContent || "").replace(/[\[\]]/g, ""),
        label: cite.dataset.label || "来源",
        detail: cite.dataset.detail || "",
        asof: cite.dataset.asof || "",
        url: cite.dataset.url || "",
        web: String(cite.dataset.id || "").startsWith("web:"),
        left: this.tipAnchor.left,
        top: this.tipAnchor.top,
        ready: false,
      };
      this.$nextTick(() => this.positionTip());
    },
    positionTip() {
      const element = this.$refs.tipEl;
      if (!element || !this.tipAnchor) return;
      const width = element.offsetWidth;
      const height = element.offsetHeight;
      let left = this.tipAnchor.left + this.tipAnchor.width / 2 - width / 2;
      left = Math.max(10, Math.min(left, window.innerWidth - width - 10));
      let top = this.tipAnchor.top - height - 10;
      if (top < 10) top = this.tipAnchor.bottom + 10;
      if (top + height > window.innerHeight - 10) {
        top = Math.max(10, window.innerHeight - height - 10);
      }
      this.tip = { ...this.tip, left, top, ready: true };
    },
    scheduleHide() {
      if (this.hideTimer) clearTimeout(this.hideTimer);
      this.hideTimer = setTimeout(() => {
        this.tip = null;
        this.hideTimer = null;
      }, 150);
    },
    cancelHide() {
      if (this.hideTimer) { clearTimeout(this.hideTimer); this.hideTimer = null; }
    },
    hideTip() {
      this.cancelHide();
      this.tip = null;
    },
    onTipOut(event) {
      const related = event.relatedTarget;
      if (related && related.closest && related.closest(".tip")) return;
      this.scheduleHide();
    },
  },
};

// ---------------------------------------------------------------------------
// 日 K 面板：不引图表库，直接算坐标画 SVG。
// ---------------------------------------------------------------------------
function klineMA(bars, period) {
  const out = new Array(bars.length).fill(null);
  let sum = 0;
  for (let i = 0; i < bars.length; i += 1) {
    sum += bars[i].close;
    if (i >= period) sum -= bars[i - period].close;
    if (i >= period - 1) out[i] = sum / period;
  }
  return out;
}

function klineLayout(rawBars, series) {
  const bars = (rawBars || []).filter(
    (bar) => bar && [bar.open, bar.close, bar.high, bar.low].every((v) => Number.isFinite(v))
  );
  if (!bars.length) return null;

  const W = 320;
  const TOP = 8;
  const PRICE_H = 300;
  const GAP = 18;
  const VOL_H = 70;
  const LEFT = 2;
  const RIGHT = 46;
  const BOTTOM = 20;
  const H = TOP + PRICE_H + GAP + VOL_H + BOTTOM;
  const step = (W - LEFT - RIGHT) / bars.length;
  const bodyW = Math.max(1, step * 0.62);
  const x = (i) => LEFT + (i + 0.5) * step;

  // 均线要在完整序列上算好再切窗口，否则平移后窗口左边缘的 MA 会偏
  const ma5 = series && series.ma5 ? series.ma5 : klineMA(bars, 5);
  const ma10 = series && series.ma10 ? series.ma10 : klineMA(bars, 10);
  const ma20 = series && series.ma20 ? series.ma20 : klineMA(bars, 20);
  let lo = Infinity;
  let hi = -Infinity;
  bars.forEach((bar, i) => {
    lo = Math.min(lo, bar.low);
    hi = Math.max(hi, bar.high);
    [ma5, ma10, ma20].forEach((values) => {
      if (values[i] != null) {
        lo = Math.min(lo, values[i]);
        hi = Math.max(hi, values[i]);
      }
    });
  });
  if (!(hi > lo)) {
    hi = lo + 1;
    lo -= 1;
  }
  const yPrice = (value) => TOP + ((hi - value) / (hi - lo)) * PRICE_H;
  const maxVol = Math.max(1, ...bars.map((bar) => bar.volume || 0));
  const volTop = TOP + PRICE_H + GAP;

  const candles = bars.map((bar, i) => {
    const top = yPrice(Math.max(bar.open, bar.close));
    const bottom = yPrice(Math.min(bar.open, bar.close));
    return {
      date: bar.date,
      up: bar.close >= bar.open,
      x: x(i) - bodyW / 2,
      cx: x(i),
      bodyY: top,
      bodyH: Math.max(1, bottom - top),
      wickTop: yPrice(bar.high),
      wickBottom: yPrice(bar.low),
      closeY: yPrice(bar.close),
      width: bodyW,
    };
  });
  const volumes = bars.map((bar, i) => {
    const height = ((bar.volume || 0) / maxVol) * VOL_H;
    return {
      x: x(i) - bodyW / 2,
      y: volTop + VOL_H - height,
      width: bodyW,
      height: Math.max(1, height),
      up: bar.close >= bar.open,
    };
  });
  const line = (values) =>
    values
      .map((value, i) => (value == null ? null : `${x(i).toFixed(1)},${yPrice(value).toFixed(1)}`))
      .filter(Boolean)
      .join(" ");
  const lastDate = bars[bars.length - 1].date || "";

  return {
    W,
    H,
    bars,
    candles,
    volumes,
    step,
    plotLeft: LEFT,
    plotRight: W - RIGHT,
    priceTop: TOP,
    priceBottom: TOP + PRICE_H,
    volTop,
    volBottom: volTop + VOL_H,
    ma5: line(ma5),
    ma10: line(ma10),
    ma20: line(ma20),
    ma5Last: ma5[ma5.length - 1],
    ma10Last: ma10[ma10.length - 1],
    ma20Last: ma20[ma20.length - 1],
    grid: [hi, (hi + lo) / 2, lo].map((value) => ({ y: yPrice(value), label: value.toFixed(2) })),
    firstDate: (bars[0].date || "").slice(5),
    lastDate: lastDate.slice(5),
    dateX1: LEFT,
    dateX2: W - RIGHT,
    dateY: H - 3,
  };
}

const KlineChart = {
  props: {
    code: { type: String, default: "" },
    name: { type: String, default: "" },
    bars: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false },
    error: { type: String, default: "" },
  },
  data() {
    // end = 窗口右端（不含）；null 表示跟着最新一根走
    return { hover: null, zoom: 120, end: null, dragging: false };
  },
  computed: {
    fullBars() {
      return (this.bars || []).filter(
        (bar) => bar && [bar.open, bar.close, bar.high, bar.low].every((v) => Number.isFinite(v))
      );
    },
    mas() {
      const bars = this.fullBars;
      return { ma5: klineMA(bars, 5), ma10: klineMA(bars, 10), ma20: klineMA(bars, 20) };
    },
    windowSize() {
      return Math.min(this.fullBars.length, Math.max(20, this.zoom));
    },
    windowEnd() {
      const len = this.fullBars.length;
      if (this.end == null) return len;
      return Math.max(this.windowSize, Math.min(len, this.end));
    },
    windowStart() {
      return this.windowEnd - this.windowSize;
    },
    visible() {
      const start = this.windowStart;
      const end = this.windowEnd;
      const mas = this.mas;
      return {
        start,
        end,
        bars: this.fullBars.slice(start, end),
        ma5: mas.ma5.slice(start, end),
        ma10: mas.ma10.slice(start, end),
        ma20: mas.ma20.slice(start, end),
      };
    },
    layout() {
      return klineLayout(this.visible.bars, this.visible);
    },
    rangeText() {
      const bars = this.visible.bars;
      if (!bars.length) return "";
      return `${bars[0].date} ~ ${bars[bars.length - 1].date}`;
    },
    quote() {
      const bars = this.bars || [];
      if (bars.length < 2) return null;
      const last = bars[bars.length - 1];
      const prev = bars[bars.length - 2];
      if (!Number.isFinite(last.close) || !Number.isFinite(prev.close) || !prev.close) return null;
      const change = last.close - prev.close;
      return { close: last.close, change, pct: (change / prev.close) * 100 };
    },
    hoverBar() {
      const layout = this.layout;
      if (!layout || this.hover == null) return null;
      const index = Math.min(this.hover, layout.bars.length - 1);
      const bar = layout.bars[index];
      const candle = layout.candles[index];
      const prev = index > 0 ? layout.bars[index - 1] : null;
      const change = prev && Number.isFinite(prev.close) ? bar.close - prev.close : null;
      return {
        ...bar,
        cx: candle.cx,
        closeY: candle.closeY,
        change,
        pct: change != null && prev.close ? (change / prev.close) * 100 : null,
      };
    },
    hoverLeft() {
      if (!this.hoverBar || !this.layout) return 50;
      return Math.min(84, Math.max(16, (this.hoverBar.cx / this.layout.W) * 100));
    },
  },
  methods: {
    maText(value) {
      return Number.isFinite(value) ? value.toFixed(2) : "--";
    },
    priceText(value) {
      return Number.isFinite(value) ? value.toFixed(2) : "--";
    },
    volText(value) {
      // KlineBar.volume 单位是股；÷1e6 就是万手
      return Number.isFinite(value) ? `${(value / 1e6).toFixed(1)} 万手` : "--";
    },
    amountText(value) {
      return Number.isFinite(value) ? `${(value / 1e8).toFixed(2)} 亿` : "--";
    },
    onMove(event) {
      const layout = this.layout;
      if (!layout || this.dragging) return;
      const rect = event.currentTarget.getBoundingClientRect();
      const vbX = ((event.clientX - rect.left) / rect.width) * layout.W;
      // 第 i 根的中心在 plotLeft + (i + 0.5) * step
      const index = Math.round((vbX - layout.plotLeft) / layout.step - 0.5);
      this.hover = Math.max(0, Math.min(layout.bars.length - 1, index));
    },
    clearHover() {
      this.hover = null;
    },
    // 拖动平移：往右拖看更早的行情
    startDrag(event) {
      const layout = this.layout;
      if (!layout || this.fullBars.length <= this.windowSize) return;
      const rect = event.currentTarget.getBoundingClientRect();
      const pxPerBar = (rect.width * layout.step) / layout.W;
      const startX = event.clientX;
      const startEnd = this.windowEnd;
      this.hover = null;
      this.dragging = true;
      const move = (e) => {
        const delta = Math.round((startX - e.clientX) / pxPerBar);
        this.end = Math.max(this.windowSize, Math.min(this.fullBars.length, startEnd + delta));
      };
      const up = () => {
        this.dragging = false;
        window.removeEventListener("mousemove", move);
        window.removeEventListener("mouseup", up);
      };
      window.addEventListener("mousemove", move);
      window.addEventListener("mouseup", up);
    },
    // 滚轮缩放：以光标所在的交易日为锚点
    onWheel(event) {
      const layout = this.layout;
      const all = this.fullBars;
      if (!layout || all.length <= 1) return;
      const rect = event.currentTarget.getBoundingClientRect();
      const vbX = ((event.clientX - rect.left) / rect.width) * layout.W;
      const raw = this.windowStart + Math.round((vbX - layout.plotLeft) / layout.step - 0.5);
      const anchor = Math.max(0, Math.min(all.length - 1, raw));
      const next = Math.min(all.length, Math.max(20, Math.round(this.windowSize * (event.deltaY > 0 ? 1.25 : 0.8))));
      if (next === this.windowSize) return;
      const ratio = (anchor - this.windowStart + 0.5) / this.windowSize;
      let start = Math.round(anchor - ratio * next);
      start = Math.max(0, Math.min(all.length - next, start));
      this.zoom = next;
      this.end = start + next;
      this.hover = null;
    },
    resetView() {
      this.zoom = 120;
      this.end = null;
      this.hover = null;
    },
  },
  watch: {
    code() {
      this.zoom = 120;
      this.end = null;
      this.hover = null;
    },
  },
  template: `
    <div class="kline-wrap">
      <header class="kline-head">
        <div class="kline-co">
          <span class="kline-name">{{ name || code }}</span>
          <span class="kline-code">{{ code }}</span>
        </div>
        <div class="kline-quote" v-if="quote">
          <span class="kline-price">{{ quote.close.toFixed(2) }}</span>
          <span class="kline-chg" :class="quote.change >= 0 ? 'up' : 'down'">
            {{ quote.change >= 0 ? "+" : "" }}{{ quote.change.toFixed(2) }} · {{ quote.pct.toFixed(2) }}%
          </span>
        </div>
      </header>

      <p class="kline-status" v-if="loading">正在加载日线…</p>
      <p class="kline-status error" v-else-if="error">{{ error }}</p>
      <template v-else-if="layout">
        <div class="kline-legend">
          <span class="ma ma5">MA5 {{ maText(layout.ma5Last) }}</span>
          <span class="ma ma10">MA10 {{ maText(layout.ma10Last) }}</span>
          <span class="ma ma20">MA20 {{ maText(layout.ma20Last) }}</span>
        </div>
        <svg
          class="kline-svg"
          :class="{ dragging: dragging }"
          :viewBox="'0 0 ' + layout.W + ' ' + layout.H"
          role="img"
          :aria-label="(name || code) + ' 日K线'"
          @mousemove="onMove"
          @mouseleave="clearHover"
          @mousedown.prevent="startDrag"
          @wheel.prevent="onWheel"
          @dblclick="resetView"
        >
          <g class="kline-grid">
            <line v-for="row in layout.grid" :key="'g' + row.y" x1="0" :x2="layout.W - 46" :y1="row.y" :y2="row.y" />
            <text v-for="row in layout.grid" :key="'t' + row.y" :x="layout.W - 40" :y="row.y" dominant-baseline="middle">{{ row.label }}</text>
          </g>
          <g v-for="bar in layout.candles" :key="'c' + bar.date">
            <line class="wick" :class="bar.up ? 'up' : 'down'" :x1="bar.cx" :x2="bar.cx" :y1="bar.wickTop" :y2="bar.wickBottom" />
            <rect class="body" :class="bar.up ? 'up' : 'down'" :x="bar.x" :y="bar.bodyY" :width="bar.width" :height="bar.bodyH" />
          </g>
          <polyline class="ma-line ma5" :points="layout.ma5" />
          <polyline class="ma-line ma10" :points="layout.ma10" />
          <polyline class="ma-line ma20" :points="layout.ma20" />
          <g class="kline-vol">
            <rect v-for="(bar, index) in layout.volumes" :key="'v' + index" :class="bar.up ? 'up' : 'down'" :x="bar.x" :y="bar.y" :width="bar.width" :height="bar.height" />
          </g>
          <g class="kline-cross" v-if="hoverBar">
            <line :x1="hoverBar.cx" :x2="hoverBar.cx" :y1="layout.priceTop" :y2="layout.volBottom" />
            <line class="h" x1="0" :x2="layout.plotRight" :y1="hoverBar.closeY" :y2="hoverBar.closeY" />
          </g>
          <g class="kline-dates">
            <text :x="layout.dateX1" :y="layout.dateY" text-anchor="start">{{ layout.firstDate }}</text>
            <text :x="layout.dateX2" :y="layout.dateY" text-anchor="end">{{ layout.lastDate }}</text>
          </g>
        </svg>
        <div class="kline-tip" v-if="hoverBar" :style="{ left: hoverLeft + '%' }">
          <div class="kline-tip-date">{{ hoverBar.date }}</div>
          <div class="kline-tip-grid">
            <span>开</span><b>{{ priceText(hoverBar.open) }}</b>
            <span>高</span><b>{{ priceText(hoverBar.high) }}</b>
            <span>收</span><b>{{ priceText(hoverBar.close) }}</b>
            <span>低</span><b>{{ priceText(hoverBar.low) }}</b>
          </div>
          <div class="kline-tip-meta">量 {{ volText(hoverBar.volume) }} · 额 {{ amountText(hoverBar.amount) }}</div>
          <div class="kline-tip-meta" v-if="hoverBar.pct != null" :class="hoverBar.pct >= 0 ? 'up' : 'down'">
            {{ hoverBar.pct >= 0 ? "+" : "" }}{{ hoverBar.pct.toFixed(2) }}%
          </div>
        </div>
        <div class="kline-foot">
          {{ rangeText }} · 共 {{ fullBars.length }} 根<br />
          拖动平移 · 滚轮缩放 · 双击回到最新
        </div>
      </template>
      <p class="kline-status" v-else>暂无日线数据</p>
    </div>
  `,
};

// ---------------------------------------------------------------------------
// 页面：左边常驻一个对话栏，右边按需再开一个。
// ---------------------------------------------------------------------------
createApp({
  components: { ChatPane, EvidenceView, KlineChart },
  data() {
    return {
      conversations: [],
      search: "",
      leftCid: null,
      rightCid: null,
      splitOpen: false,
      splitRatio: 52,
      dragging: false,
      resizing: false,
      sideWidth: 288,
      sideResizing: false,
      sourceView: null,
      sourceWidth: 380,
      // 日 K 面板跟随左侧对话的公司；来源详情打开时让位给它
      kline: { code: "", name: "", bars: [], loading: false, error: "" },
      klineWidth: 340,
      klineResizing: false,
      sourceResizing: false,
      evidence: null,
      evidenceState: "idle",
      archiveOpen: false,
      archiveList: [],
      archiveDetail: null,
      archiveCode: null,
      archiveSection: "conclusions",
      archiveLoading: false,
      archiveError: "",
      archiveQuery: "",
      metricsOpen: false,
      metrics: null,
      metricsError: "",
      metricsTimer: null,
      // 交易页：trading 是 /api/trading/state 的完整快照，阶段详情按需缓存
      tradingOpen: false,
      tradingLoading: false,
      tradingError: "",
      tradingDate: "",
      tradingDates: [],
      tradingArchive: [],
      tradingExperience: { content: "", updated_at: null },
      trading: null,
      tradingPhaseId: null,
      tradingPhaseDetail: null,
      tradingPhaseCache: {},
      tradingPhaseTab: "answer",
      tradingLive: { text: "", thinking: "", tools: [] },
      tradingStream: null,
      tradingRetry: 0,
      tradingTimer: null,
      tradingCite: null,
    };
  },
  computed: {
    filteredConversations() {
      const needle = this.search.trim().toLowerCase();
      if (!needle) return this.conversations;
      return this.conversations.filter((item) =>
        [item.title, item.name, item.code]
          .filter(Boolean)
          .some((text) => String(text).toLowerCase().includes(needle))
      );
    },
    leftStyle() {
      if (!this.splitOpen) return { flex: "1 1 auto", minWidth: 0 };
      return { flex: `0 0 ${this.splitRatio}%`, minWidth: 0 };
    },
    rightStyle() {
      return { flex: "1 1 auto", minWidth: 0 };
    },
    hasEvidenceData() {
      if (!this.evidence) return false;
      const value = this.evidence.data;
      if (value === null || value === undefined) return false;
      if (Array.isArray(value)) return value.length > 0;
      if (typeof value === "object") return Object.keys(value).length > 0;
      return value !== "";
    },
    evidenceJson() {
      if (!this.hasEvidenceData) return "";
      let text = "";
      try {
        text = JSON.stringify(this.evidence.data, null, 2);
      } catch (error) {
        return "";
      }
      return text.length > 12000 ? `${text.slice(0, 12000)}\n…（已截断）` : text;
    },
    klineOpen() {
      return Boolean(this.kline.code) && !this.sourceView && !this.archiveOpen && !this.metricsOpen && !this.tradingOpen;
    },
    archiveCodes() {
      return this.archiveList.map((item) => item.code);
    },
    filteredArchives() {
      const needle = this.archiveQuery.trim().toLowerCase();
      if (!needle) return this.archiveList;
      return this.archiveList.filter((item) =>
        [item.name, item.code, item.conclusion]
          .filter(Boolean)
          .some((text) => String(text).toLowerCase().includes(needle))
      );
    },
    archiveTabs() {
      const counts = (this.archiveDetail && this.archiveDetail.counts) || {};
      return [
        { key: "conclusions", label: "结论", count: counts.conclusions || 0 },
        { key: "facts", label: "事实", count: counts.facts || 0 },
        { key: "hypotheses", label: "假设", count: counts.hypotheses || 0 },
        { key: "changes", label: "变化", count: counts.changes || 0 },
        { key: "questions", label: "问题", count: counts.questions || 0 },
      ];
    },
    archiveConclusions() {
      const items = ((this.archiveDetail && this.archiveDetail.conclusions) || []).slice();
      const rank = (item) => (item.status === "有效" ? 0 : 1);
      items.sort(
        (a, b) =>
          rank(a) - rank(b) ||
          String(b.updated_at || "").localeCompare(String(a.updated_at || ""))
      );
      return items;
    },
    archiveFactGroups() {
      const groups = [];
      const index = new Map();
      for (const fact of (this.archiveDetail && this.archiveDetail.facts) || []) {
        const type = fact.fact_type || "未分类";
        if (!index.has(type)) {
          index.set(type, groups.length);
          groups.push({ type, items: [] });
        }
        groups[index.get(type)].items.push(fact);
      }
      return groups;
    },
    archiveQuestionGroups() {
      const items = (this.archiveDetail && this.archiveDetail.questions) || [];
      const importanceOrder = ["高", "中", "低"];
      const statusOrder = ["待处理", "调查中", "已解决", "暂时无法解决"];
      const groups = [];
      for (const importance of importanceOrder.concat("其他")) {
        const bucket = items.filter((item) =>
          importance === "其他"
            ? !importanceOrder.includes(item.importance)
            : item.importance === importance
        );
        if (!bucket.length) continue;
        bucket.sort((a, b) => {
          const ai = statusOrder.indexOf(a.status);
          const bi = statusOrder.indexOf(b.status);
          return (ai < 0 ? statusOrder.length : ai) - (bi < 0 ? statusOrder.length : bi);
        });
        groups.push({ importance, items: bucket });
      }
      return groups;
    },
    heapShare() {
      const system = (this.metrics && this.metrics.system) || {};
      return system.heapMax > 0 ? system.heapUsed / system.heapMax : 0;
    },
    memoryShare() {
      const system = (this.metrics && this.metrics.system) || {};
      return system.memoryTotal > 0 ? system.memoryUsed / system.memoryTotal : 0;
    },
    uptimeText() {
      const ms = ((this.metrics && this.metrics.system && this.metrics.system.uptimeMs) || 0);
      const seconds = Math.floor(ms / 1000);
      const minutes = Math.floor(seconds / 60);
      const hours = Math.floor(minutes / 60);
      if (hours) return `${hours} 小时 ${minutes % 60} 分`;
      if (minutes) return `${minutes} 分 ${seconds % 60} 秒`;
      return `${seconds} 秒`;
    },
    sortedTools() {
      return [...((this.metrics && this.metrics.tools) || [])].sort(
        (a, b) => b.count - a.count || String(a.name).localeCompare(String(b.name))
      );
    },
    todayStatusText() {
      if (!this.trading) return "";
      const status = this.trading.today_status;
      if (!status) return "历史日期";
      return { TRADING: "交易日", HOLIDAY: "休市", UNCONFIRMED: "交易日未确认" }[status] || status;
    },
    todayStatusClass() {
      const status = this.trading && this.trading.today_status;
      return status ? `trading-status-${status.toLowerCase()}` : "";
    },
    planHtml() {
      if (!this.trading || !this.trading.plan) return "";
      return renderBody(this.trading.plan.notes || "", this.sourcesOf(this.trading.plan.phase));
    },
    liveHtml() {
      return renderBody(this.tradingLive.text || "", this.liveSources(), true);
    },
    phaseAnswerHtml() {
      const detail = this.tradingPhaseDetail;
      return detail ? renderBody(detail.answer || "", detail.sources || []) : "";
    },
    phaseThinking() {
      const detail = this.tradingPhaseDetail;
      return detail && detail.thinking ? String(detail.thinking) : "";
    },
    phaseTrace() {
      return (this.tradingPhaseDetail && this.tradingPhaseDetail.trace) || [];
    },
    phaseRawJson() {
      return prettyJson(this.tradingPhaseDetail && this.tradingPhaseDetail.raw);
    },
    phaseRunJson() {
      return prettyJson(this.tradingPhaseDetail && this.tradingPhaseDetail.run);
    },
    phaseIssues() {
      return (this.tradingPhaseDetail && this.tradingPhaseDetail.issues) || [];
    },
    phaseSources() {
      return (this.tradingPhaseDetail && this.tradingPhaseDetail.sources) || [];
    },
  },
  methods: {
    metaLine(item) {
      const parts = [];
      const date = shortDate(item.updated);
      if (date) parts.push(date);
      if (item.count) parts.push(`${item.count} 条`);
      if (item.id === this.leftCid) parts.push("当前");
      if (item.id === this.rightCid) parts.push("分屏");
      return parts.join(" · ");
    },

    shortDate,
    percentText,
    fileSize,
    formatDuration,

    async refresh() {
      const archives = this.loadArchives();
      try {
        const response = await apiFetch("/api/conversations");
        if (!response.ok) return;
        this.conversations = await response.json();
      } catch (error) { /* 列表失败不影响聊天 */ }
      await archives;
    },

    // ---------------- 研究档案 ----------------
    async loadArchives() {
      try {
        const response = await apiFetch("/api/research/companies");
        if (!response.ok) return;
        const data = await response.json();
        this.archiveList = Array.isArray(data.companies) ? data.companies : [];
      } catch (error) {
        this.archiveList = [];
      }
    },
    openArchive(code = "") {
      this.closeSource();
      this.closeMetrics();
      this.archiveOpen = true;
      if (!this.archiveList.length) this.loadArchives();
      if (code) {
        this.archiveCode = code;
        this.loadArchiveDetail(code);
      } else {
        this.archiveCode = null;
        this.archiveDetail = null;
        this.archiveError = "";
        this.archiveLoading = false;
      }
    },
    closeArchive() {
      this.archiveOpen = false;
      this.archiveCode = null;
      this.archiveDetail = null;
      this.archiveError = "";
      this.archiveLoading = false;
    },
    showArchiveList() {
      this.archiveDetail = null;
      this.archiveCode = null;
      this.archiveError = "";
      this.archiveLoading = false;
    },

    // ---------------- 系统监控 ----------------
    openMetrics() {
      this.closeSource();
      this.archiveOpen = false;
      this.metricsOpen = true;
      this.loadMetrics();
      this.stopMetricsTimer();
      this.metricsTimer = setInterval(() => this.loadMetrics(), 3000);
    },
    closeMetrics() {
      this.metricsOpen = false;
      this.stopMetricsTimer();
    },
    stopMetricsTimer() {
      if (this.metricsTimer) {
        clearInterval(this.metricsTimer);
        this.metricsTimer = null;
      }
    },
    async loadMetrics() {
      try {
        const response = await apiFetch("/api/metrics");
        if (!response.ok) {
          this.metricsError = "监控读取失败，请稍后重试";
          return;
        }
        this.metrics = await response.json();
        this.metricsError = "";
      } catch (error) {
        this.metricsError = "监控读取失败，请稍后重试";
      }
    },
    barWidth(share) {
      const value = Number(share);
      if (!Number.isFinite(value) || value <= 0) return "0%";
      return `${Math.min(100, Math.round(value * 100))}%`;
    },

    // ---------------- 交易页 ----------------
    openTrading() {
      this.closeSource();
      this.archiveOpen = false;
      this.closeMetrics();
      this.tradingOpen = true;
      this.tradingDate = "";
      this.tradingPhaseId = null;
      this.tradingPhaseDetail = null;
      this.tradingPhaseCache = {};
      this.tradingPhaseTab = "answer";
      this.tradingLive = { text: "", thinking: "", tools: [] };
      this.tradingCite = null;
      this.tradingError = "";
      this.loadTradingDates();
      this.loadTradingArchive();
      this.loadTradingExperience();
      this.loadTradingState();
      this.stopTradingTimer();
      this.tradingTimer = setInterval(() => this.loadTradingState(), 5000);
      this.connectTradingStream();
    },
    closeTrading() {
      this.tradingOpen = false;
      this.stopTradingTimer();
      this.closeTradingStream();
      this.tradingCite = null;
    },
    stopTradingTimer() {
      if (this.tradingTimer) {
        clearInterval(this.tradingTimer);
        this.tradingTimer = null;
      }
    },
    closeTradingStream() {
      if (this.tradingStream) {
        try {
          this.tradingStream.abort();
        } catch (error) { /* 已断开 */ }
        this.tradingStream = null;
      }
    },
    selectTradingDate(value) {
      this.tradingDate = value || "";
      this.tradingPhaseId = null;
      this.tradingPhaseDetail = null;
      this.tradingPhaseCache = {};
      this.tradingPhaseTab = "answer";
      this.tradingLive = { text: "", thinking: "", tools: [] };
      this.loadTradingState();
    },
    async loadTradingDates() {
      try {
        const response = await apiFetch("/api/trading/dates");
        if (!response.ok) return;
        const data = await response.json();
        this.tradingDates = Array.isArray(data.dates) ? data.dates : [];
      } catch (error) {
        this.tradingDates = [];
      }
    },
    // 复盘归档是跨日期的长期记忆，只在打开页面和阶段结束时刷新
    async loadTradingArchive() {
      try {
        const response = await apiFetch("/api/trading/journals?limit=200");
        if (!response.ok) return;
        const data = await response.json();
        this.tradingArchive = Array.isArray(data.journals) ? data.journals : [];
      } catch (error) {
        this.tradingArchive = [];
      }
    },
    // 经验文档只在收盘阶段变，打开页面和阶段结束时刷新即可
    async loadTradingExperience() {
      try {
        const response = await apiFetch("/api/trading/experience");
        if (!response.ok) return;
        const data = await response.json();
        this.tradingExperience = {
          content: data.content || "",
          updated_at: data.updated_at || null,
        };
      } catch (error) {
        this.tradingExperience = { content: "", updated_at: null };
      }
    },
    async loadTradingState() {
      if (!this.tradingOpen) return;
      this.tradingLoading = true;
      try {
        const query = this.tradingDate ? `?date=${encodeURIComponent(this.tradingDate)}` : "";
        const response = await apiFetch(`/api/trading/state${query}`);
        if (!this.tradingOpen) return;
        if (!response.ok) {
          this.tradingError = "交易状态读取失败，请稍后重试";
          return;
        }
        this.trading = await response.json();
        this.tradingError = "";
        await this.ensurePhaseDetails();
      } catch (error) {
        this.tradingError = "交易状态读取失败，请稍后重试";
      } finally {
        this.tradingLoading = false;
      }
    },
    // 每个阶段的完整 payload 都缓存；状态变了才重新拉，保证引用可点
    async ensurePhaseDetails() {
      const phases = (this.trading && this.trading.phases) || [];
      for (const phase of phases) {
        const cached = this.tradingPhaseCache[phase.id];
        if (cached && cached.status === phase.status) continue;
        try {
          const response = await apiFetch(`/api/trading/phases/${phase.id}`);
          if (!response.ok) continue;
          const detail = await response.json();
          this.tradingPhaseCache = { ...this.tradingPhaseCache, [phase.id]: detail };
          if (this.tradingPhaseId === phase.id) this.tradingPhaseDetail = detail;
        } catch (error) { /* 单个阶段失败不影响其它内容 */ }
      }
      if (this.tradingPhaseId == null && phases.length) {
        const latest = phases[phases.length - 1];
        this.tradingPhaseId = latest.id;
        this.tradingPhaseDetail = this.tradingPhaseCache[latest.id] || null;
      }
    },
    async loadTradingPhase(id) {
      this.tradingPhaseId = id;
      this.tradingPhaseTab = "answer";
      const cached = this.tradingPhaseCache[id];
      if (cached) {
        this.tradingPhaseDetail = cached;
        return;
      }
      try {
        const response = await apiFetch(`/api/trading/phases/${id}`);
        if (!response.ok) {
          this.tradingError = "阶段详情读取失败";
          return;
        }
        const detail = await response.json();
        this.tradingPhaseCache = { ...this.tradingPhaseCache, [id]: detail };
        this.tradingPhaseDetail = detail;
      } catch (error) {
        this.tradingError = "阶段详情读取失败";
      }
    },
    async connectTradingStream() {
      this.closeTradingStream();
      const controller = new AbortController();
      this.tradingStream = controller;
      const headers = {};
      const token = localStorage.getItem(TOKEN_KEY);
      if (token) headers.Authorization = `Bearer ${token}`;
      try {
        const response = await fetch("/api/trading/stream", { headers, signal: controller.signal });
        if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}`);
        this.tradingRetry = 0;
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        while (true) {
          const chunk = await reader.read();
          if (chunk.done) break;
          buffer += decoder.decode(chunk.value, { stream: true });
          let split;
          while ((split = buffer.indexOf("\n\n")) >= 0) {
            const frame = buffer.slice(0, split);
            buffer = buffer.slice(split + 2);
            const line = frame.split("\n").find((item) => item.startsWith("data:"));
            if (!line) continue;
            let event;
            try {
              event = JSON.parse(line.slice(5).trim());
            } catch (error) {
              continue;
            }
            this.onTradingEvent(event);
          }
        }
      } catch (error) {
        if (error.name === "AbortError" || !this.tradingOpen) return;
        // 指数退避重连；断开期间仍靠 5 秒轮询兜底
        this.tradingRetry = Math.min(this.tradingRetry + 1, 4);
        const delay = Math.min(30000, 3000 * Math.pow(2, this.tradingRetry - 1));
        this.tradingError = `实时流断开，${Math.round(delay / 1000)} 秒后重连`;
        setTimeout(() => {
          if (this.tradingOpen) this.connectTradingStream();
        }, delay);
      } finally {
        if (this.tradingStream === controller) this.tradingStream = null;
      }
    },
    onTradingEvent(event) {
      if (!this.tradingOpen) return;
      if (event.type === "thinking") {
        this.tradingLive.thinking += event.delta || "";
      } else if (event.type === "text") {
        this.tradingLive.text += event.delta || "";
      } else if (event.type === "tool") {
        this.tradingLive.tools.push(event);
      } else if (event.type === "phase") {
        if (event.event === "started") {
          this.tradingLive = { text: "", thinking: "", tools: [] };
        } else {
          // 阶段结束往往带回新的复盘与经验，归档要同步更新
          this.loadTradingArchive();
          this.loadTradingExperience();
        }
        this.loadTradingState();
      } else if (event.type === "monitor") {
        this.loadTradingState();
      } else if (event.type === "error") {
        this.tradingError = event.text || "交易出错";
      }
    },
    sourcesOf(phaseName) {
      const phase = ((this.trading && this.trading.phases) || []).find(
        (item) => item.phase === phaseName
      );
      const detail = phase && this.tradingPhaseCache[phase.id];
      return (detail && detail.sources) || [];
    },
    liveSources() {
      const phases = (this.trading && this.trading.phases) || [];
      if (!phases.length) return [];
      const detail = this.tradingPhaseCache[phases[phases.length - 1].id];
      return (detail && detail.sources) || [];
    },
    experienceHtml() {
      return renderBody(this.tradingExperience.content || "", []);
    },
    journalHtml(journal) {
      // 档案条目自带当时阶段的 sources；当日复盘没有时回落到已缓存的阶段详情
      return renderBody(journal.text || "", journal.sources || this.sourcesOf(journal.phase));
    },
    onTradingCite(event) {
      const target = event.target;
      const cite = target && target.closest ? target.closest(".cite") : null;
      if (!cite) return;
      event.stopPropagation();
      const id = cite.getAttribute("data-id") || "";
      let found = null;
      for (const detail of Object.values(this.tradingPhaseCache)) {
        found = (detail.sources || []).find((source) => String(source.id) === id);
        if (found) break;
      }
      this.tradingCite =
        found || {
          id,
          label: cite.getAttribute("data-label") || id,
          detail: cite.getAttribute("data-detail") || "",
          as_of: cite.getAttribute("data-asof") || "",
          url: cite.getAttribute("data-url") || "",
        };
    },
    showTradingSource(source) {
      this.tradingCite = source;
    },
    phaseLabel(phase) {
      return { pre_market: "盘前", midday: "午盘", close: "收盘" }[phase] || phase || "--";
    },
    phaseStatusLabel(status) {
      return { RUNNING: "进行中", OK: "完成", ERROR: "失败" }[status] || status || "--";
    },
    itemStatusLabel(status) {
      return {
        PENDING: "待成交",
        FILLED: "已成交",
        CANCELLED: "已取消",
        REJECTED: "已拒绝",
        EXPIRED: "已过期",
      }[status] || status || "--";
    },
    sideLabel(side) {
      return { BUY: "买入", SELL: "卖出" }[side] || side || "--";
    },
    money(cents) {
      const value = (Number(cents) || 0) / 100;
      return value.toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    },
    price(cents) {
      if (cents === null || cents === undefined) return "--";
      return (Number(cents) / 100).toFixed(2);
    },
    signedMoney(cents) {
      const value = Number(cents) || 0;
      const sign = value > 0 ? "+" : value < 0 ? "-" : "";
      return `${sign}¥${this.money(Math.abs(value))}`;
    },
    pctText(value, base) {
      const total = Number(base) || 0;
      if (!total) return "--";
      const pct = ((Number(value) || 0) / total) * 100;
      return `${pct > 0 ? "+" : ""}${pct.toFixed(2)}%`;
    },
    pnlClass(value) {
      const number = Number(value) || 0;
      return number > 0 ? "pnl-up" : number < 0 ? "pnl-down" : "";
    },
    shortTime(text) {
      return text ? String(text).slice(11, 19) : "";
    },
    compactJson(value) {
      try {
        const text = JSON.stringify(value || {});
        return text.length > 160 ? `${text.slice(0, 160)}…` : text;
      } catch (error) {
        return String(value);
      }
    },
    async loadArchiveDetail(code) {
      const requested = code;
      this.archiveCode = code;
      this.archiveDetail = null;
      this.archiveLoading = true;
      this.archiveError = "";
      try {
        const response = await apiFetch(`/api/research/companies/${encodeURIComponent(code)}`);
        if (this.archiveCode !== requested) return;
        if (!response.ok) {
          this.archiveError = "档案读取失败，请稍后重试";
          return;
        }
        this.archiveDetail = await response.json();
        this.archiveSection = "conclusions";
      } catch (error) {
        if (this.archiveCode === requested) {
          this.archiveError = "档案读取失败，请稍后重试";
        }
      } finally {
        if (this.archiveCode === requested) this.archiveLoading = false;
      }
    },
    badgeClass(status) {
      const text = String(status || "");
      if (["有效", "已证实", "已解决"].includes(text)) return "ok";
      if (["已推翻", "已证伪", "暂时无法解决"].includes(text)) return "err";
      if (["待验证", "部分支持", "调查中", "待核验", "待处理"].includes(text)) return "warn";
      return "";
    },
    refLabels(item) {
      const labels = []
        .concat(item.based_on_fact_ids || [])
        .concat(item.based_on_hypothesis_ids || []);
      return labels.join("、");
    },

    // ---------------- 打开对话 ----------------
    openLeft(id) {
      const pane = this.$refs.leftPane;
      if (pane) pane.open(id);
    },
    async openInRight(id) {
      if (id === this.leftCid) return;
      if (!this.splitOpen) {
        this.splitOpen = true;
        await this.$nextTick();
      }
      const pane = this.$refs.rightPane;
      if (pane) pane.open(id);
    },
    closeSplit() {
      this.splitOpen = false;
      this.rightCid = null;
      localStorage.removeItem(SPLIT_KEY);
    },
    newChat() {
      const pane = this.$refs.leftPane;
      if (pane) pane.newChat();
    },

    // ---------------- 来源详情侧栏（页面最右侧） ----------------
    openSource(message, index, cid) {
      const sources = (message && message.sources) || [];
      const source = sources[index];
      if (!source) return;
      const id = String(source.id || "");
      if (cid !== undefined) this.sourceCid = cid;
      this.sourceMsg = message;
      this.sourceIndex = index;
      this.sourceView = {
        index,
        total: sources.length,
        n: index + 1,
        label: source.label || id || "未知来源",
        detail: source.detail || "",
        asof: source.as_of || "",
        fetched: source.fetched_at || "",
        url: source.url || "",
        web: isWebSource(source),
        id,
        steps: this.sourceSteps(message, id),
      };
      this.loadEvidence();
    },
    stepSource(delta) {
      if (!this.sourceMsg) return;
      const next = this.sourceIndex + delta;
      const total = (this.sourceMsg.sources || []).length;
      if (next < 0 || next >= total) return;
      this.openSource(this.sourceMsg, next, this.sourceCid);
    },
    closeSource() {
      this.sourceView = null;
      this.sourceMsg = null;
      this.sourceIndex = -1;
      this.sourceCid = null;
      this.evidence = null;
      this.evidenceState = "idle";
      this.evidenceToken = (this.evidenceToken || 0) + 1;
    },
    sourceWidthLimit() {
      // 左边对话区至少留 460px，侧栏最宽 760px
      return Math.max(320, Math.min(760, window.innerWidth - 460));
    },
    sideWidthLimit() {
      // 左侧对话列表最宽 460px，中间对话区至少留 620px
      return Math.max(240, Math.min(460, window.innerWidth - 620));
    },
    // 拖左侧栏右边缘调宽：往右拖变宽，宽度记在 localStorage
    startSideResize(event) {
      event.preventDefault();
      const startX = event.clientX;
      const startWidth = this.sideWidth;
      const move = (moveEvent) => {
        const next = startWidth + (moveEvent.clientX - startX);
        this.sideWidth = Math.min(Math.max(next, 220), this.sideWidthLimit());
      };
      const up = () => {
        this.sideResizing = false;
        window.removeEventListener("mousemove", move);
        window.removeEventListener("mouseup", up);
        try {
          localStorage.setItem("sideWidth", String(Math.round(this.sideWidth)));
        } catch (error) { /* 存不下来也不影响使用 */ }
      };
      this.sideResizing = true;
      window.addEventListener("mousemove", move);
      window.addEventListener("mouseup", up);
    },
    klineWidthLimit() {
      // 中间对话区至少留 520px，K 线面板最宽 640px
      return Math.max(280, Math.min(640, window.innerWidth - 520));
    },
    // 拖 K 线面板左边缘调宽：往左拖变宽，宽度记在 localStorage
    startKlineResize(event) {
      event.preventDefault();
      const startX = event.clientX;
      const startWidth = this.klineWidth;
      const move = (moveEvent) => {
        const next = startWidth + (startX - moveEvent.clientX);
        this.klineWidth = Math.min(Math.max(next, 280), this.klineWidthLimit());
      };
      const up = () => {
        this.klineResizing = false;
        window.removeEventListener("mousemove", move);
        window.removeEventListener("mouseup", up);
        try {
          localStorage.setItem("klineWidth", String(Math.round(this.klineWidth)));
        } catch (error) { /* 存不下来也不影响使用 */ }
      };
      this.klineResizing = true;
      window.addEventListener("mousemove", move);
      window.addEventListener("mouseup", up);
    },
    // 拖侧栏左边缘调宽：往左拖变宽，宽度记在 localStorage
    startSourceResize(event) {
      event.preventDefault();
      const startX = event.clientX;
      const startWidth = this.sourceWidth;
      const move = (moveEvent) => {
        const next = startWidth + (startX - moveEvent.clientX);
        this.sourceWidth = Math.min(Math.max(next, 300), this.sourceWidthLimit());
      };
      const up = () => {
        this.sourceResizing = false;
        window.removeEventListener("mousemove", move);
        window.removeEventListener("mouseup", up);
        try {
          localStorage.setItem("sourceWidth", String(Math.round(this.sourceWidth)));
        } catch (error) { /* 存不下来也不影响使用 */ }
      };
      this.sourceResizing = true;
      window.addEventListener("mousemove", move);
      window.addEventListener("mouseup", up);
    },
    // 依据不是回答里的原句，而是这次工具调用真正取回来的数据
    async loadEvidence() {
      const token = (this.evidenceToken || 0) + 1;
      this.evidenceToken = token;
      this.evidence = null;
      const sourceId = this.sourceView && this.sourceView.id;
      const cid = this.sourceCid;
      if (!sourceId || !cid) {
        this.evidenceState = "missing";
        return;
      }
      this.evidenceState = "loading";
      try {
        const response = await apiFetch(
          `/api/conversations/${cid}/evidence?source_id=${encodeURIComponent(sourceId)}`
        );
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const data = await response.json();
        if (token !== this.evidenceToken) return;
        this.evidence = data.evidence || null;
        this.evidenceState = this.evidence ? "ready" : "missing";
      } catch (error) {
        if (token !== this.evidenceToken) return;
        this.evidenceState = "error";
      }
    },
    // 哪次工具调用取回了这条来源；旧记录没有 trace 明细时为空
    sourceSteps(message, id) {
      const steps = Array.isArray(message.trace) ? message.trace : [];
      return steps
        .filter((step) => (step.sources || []).map(String).includes(id))
        .map((step) => {
          const parts = [];
          if (step.round) parts.push(`第 ${step.round} 轮`);
          if (step.company) parts.push(step.company);
          if (step.as_of) parts.push(step.as_of);
          parts.push(`耗时 ${formatDuration(step.duration_ms)}`);
          const args = this.argumentText(step.arguments);
          if (args) parts.push(args);
          return {
            name: step.name || "未知工具",
            ok: step.ok !== false,
            cached: !!step.cached,
            error: step.error || "",
            meta: parts.join(" · "),
          };
        });
    },
    argumentText(args) {
      if (!args || typeof args !== "object") return "";
      const parts = Object.entries(args)
        .filter(([key, value]) => key !== "company" && value !== null && value !== undefined && value !== "")
        .map(([key, value]) => `${key}=${typeof value === "object" ? JSON.stringify(value) : value}`);
      const text = parts.join(" · ");
      return text.length > 140 ? `${text.slice(0, 140)}…` : text;
    },

    // ---------------- 拖动分屏 ----------------
    onDragStart(event, item) {
      try {
        event.dataTransfer.setData("text/plain", String(item.id));
        event.dataTransfer.effectAllowed = "copy";
      } catch (error) { /* 老浏览器不支持就算了 */ }
      this.dragging = true;
    },
    onDragEnd() {
      this.dragging = false;
    },
    onDrop(event) {
      this.dragging = false;
      let raw = "";
      try {
        raw = event.dataTransfer.getData("text/plain");
      } catch (error) { /* 没有数据 */ }
      const id = Number(raw);
      if (id) this.openInRight(id);
    },

    // ---------------- 分栏宽度 ----------------
    startResize(event) {
      event.preventDefault();
      const panes = this.$refs.panes;
      if (!panes) return;
      const total = panes.clientWidth;
      if (!total) return;
      const startX = event.clientX;
      const startRatio = this.splitRatio;
      this.resizing = true;
      document.body.style.userSelect = "none";
      document.body.style.cursor = "col-resize";
      const onMove = (moveEvent) => {
        const delta = moveEvent.clientX - startX;
        this.splitRatio = Math.min(MAX_RATIO, Math.max(MIN_RATIO, startRatio + (delta / total) * 100));
      };
      const onUp = () => {
        window.removeEventListener("mousemove", onMove);
        window.removeEventListener("mouseup", onUp);
        this.resizing = false;
        document.body.style.userSelect = "";
        document.body.style.cursor = "";
        localStorage.setItem(RATIO_KEY, String(this.splitRatio));
      };
      window.addEventListener("mousemove", onMove);
      window.addEventListener("mouseup", onUp);
    },

    // ---------------- 两栏状态 ----------------
    // 只在换公司时取一次；notify 在流式回复里会被多次触发，同一代码不重复请求
    showKline(state) {
      const code = /^\d{6}$/.test(state.code || "") ? state.code : "";
      if (!code) {
        if (this.kline.code) this.kline = { code: "", name: "", bars: [], loading: false, error: "" };
        return;
      }
      if (code === this.kline.code) {
        if (state.name) this.kline.name = state.name;
        return;
      }
      this.kline = { code, name: state.name || "", bars: [], loading: true, error: "" };
      const seq = ++this.klineSeq;
      apiFetch(`/api/stocks/${code}/kline?days=500`)
        .then(async (response) => {
          if (!response.ok) {
            const body = await response.json().catch(() => ({}));
            throw new Error(body.error || `HTTP ${response.status}`);
          }
          return response.json();
        })
        .then((data) => {
          if (seq !== this.klineSeq) return;
          this.kline.bars = data.bars || [];
          this.kline.loading = false;
        })
        .catch((error) => {
          if (seq !== this.klineSeq) return;
          this.kline.loading = false;
          this.kline.error = error.message || "日线加载失败";
        });
    },
    onLeftState(state) {
      this.leftCid = state.id;
      if (state.id) localStorage.setItem(STORAGE_KEY, String(state.id));
      else localStorage.removeItem(STORAGE_KEY);
      document.title = state.name ? `投资助手 · ${state.name}` : "投资助手";
      this.showKline(state);
    },
    onRightState(state) {
      this.rightCid = state.id;
      if (state.id) localStorage.setItem(SPLIT_KEY, String(state.id));
      else localStorage.removeItem(SPLIT_KEY);
    },
  },
  created() {
    this.sourceMsg = null;
    this.sourceIndex = -1;
    this.klineSeq = 0;
    const savedWidth = Number(localStorage.getItem("sourceWidth"));
    if (Number.isFinite(savedWidth) && savedWidth >= 300) {
      this.sourceWidth = Math.min(savedWidth, this.sourceWidthLimit());
    }
    const savedSide = Number(localStorage.getItem("sideWidth"));
    if (Number.isFinite(savedSide) && savedSide >= 220) {
      this.sideWidth = Math.min(savedSide, this.sideWidthLimit());
    }
    const savedKline = Number(localStorage.getItem("klineWidth"));
    if (Number.isFinite(savedKline) && savedKline >= 280) {
      this.klineWidth = Math.min(savedKline, this.klineWidthLimit());
    }
    this.onEscape = (event) => {
      if (event.key === "Escape") this.closeSource();
    };
    window.addEventListener("keydown", this.onEscape);
  },
  beforeUnmount() {
    window.removeEventListener("keydown", this.onEscape);
  },
  async mounted() {
    this.splitRatio = Number(localStorage.getItem(RATIO_KEY)) || 52;
    await this.refresh();
    const left = Number(localStorage.getItem(STORAGE_KEY));
    if (left) this.$refs.leftPane.open(left);
    const right = Number(localStorage.getItem(SPLIT_KEY));
    if (right) this.openInRight(right);
    // 启动动画等这个信号；页面没有动画时它只是一个空事件
    window.dispatchEvent(new Event("app-ready"));
  },
}).mount("#app");
