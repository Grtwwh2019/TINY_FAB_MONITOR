"use strict";

const state = { data: null, taskFilter: "", historyFilter: "", graph: { scale: 1, x: 30, y: 30, dragging: false, startX: 0, startY: 0 } };
const $ = (id) => document.getElementById(id);

document.addEventListener("DOMContentLoaded", () => {
  $("applyDate").addEventListener("click", applyDate);
  $("refresh").addEventListener("click", refreshNow);
  $("shutdown").addEventListener("click", shutdownApp);
  $("processDate").addEventListener("keydown", e => { if (e.key === "Enter") applyDate(); });
  $("taskFilter").addEventListener("input", e => { state.taskFilter = e.target.value.toLowerCase(); renderTasks(); });
  $("historyFilter").addEventListener("input", e => { state.historyFilter = e.target.value.toLowerCase(); renderHistory(); });
  document.querySelectorAll(".tab").forEach(tab => tab.addEventListener("click", () => showPanel(tab)));
  setupGraphControls();
  loadDashboard();
  setInterval(loadDashboard, 10000);
});

async function loadDashboard() {
  try {
    const response = await fetch("/api/dashboard", { cache: "no-store" });
    if (!response.ok) throw new Error("无法读取监控数据");
    state.data = await response.json();
    if (document.activeElement !== $("processDate")) $("processDate").value = state.data.process_date || "";
    renderAll();
  } catch (error) { showToast(error.message); }
}

async function applyDate() {
  const processDate = $("processDate").value.trim();
  if (!/^\d{8}$/.test(processDate)) return showToast("请输入 8 位业务日期，例如 20251231");
  try {
    const response = await fetch("/api/process-date", { method:"POST", headers:{"Content-Type":"application/json"}, body:JSON.stringify({process_date:processDate}) });
    const body = await response.json();
    if (!response.ok) throw new Error(body.error || "日期更新失败");
    showToast(body.message);
    setTimeout(loadDashboard, 400);
  } catch (error) { showToast(error.message); }
}

async function refreshNow() {
  try {
    await fetch("/api/refresh", { method:"POST" });
    showToast("正在读取 Oracle 数据");
    setTimeout(loadDashboard, 350);
  } catch (_) { showToast("刷新请求失败"); }
}

async function shutdownApp() {
  if (!window.confirm("确定退出 Oracle FAB 运行监控？")) return;
  try {
    await fetch("/api/shutdown", { method:"POST" });
    document.body.innerHTML = '<div style="display:grid;place-items:center;height:100vh;font:16px Segoe UI;color:#536170">监控程序已安全退出，可以关闭此页面。</div>';
  } catch (_) { showToast("退出请求失败，请在任务管理器中结束程序"); }
}

function renderAll() {
  renderStatus(); renderSummary(); renderTasks(); renderHistory(); renderGraph();
}

function renderStatus() {
  const d = state.data;
  $("connectionDot").className = "dot " + (d.connected ? "connected" : d.last_error ? "error" : "");
  $("connectionText").textContent = d.connected ? "Oracle 已连接" : d.last_error ? "Oracle 连接失败" : "正在连接";
  $("lastPoll").textContent = "上次刷新：" + formatDateTime(d.last_poll_at);
  $("nextPoll").textContent = "下次刷新：" + formatDateTime(d.next_poll_at);
  $("pollingBadge").classList.toggle("hidden", !d.polling);
  $("errorBanner").textContent = d.last_error || "";
  $("errorBanner").classList.toggle("hidden", !d.last_error);
}

function renderSummary() {
  const tasks = state.data.tasks || [];
  $("runningCount").textContent = tasks.filter(t => ["I","E","B"].includes(t.status)).length;
  $("completedCount").textContent = tasks.filter(t => t.completed_at || t.status === "R").length;
  $("anomalyCount").textContent = tasks.filter(t => (t.anomaly_times || []).length > 0 || t.status === "E").length;
  $("historyCount").textContent = state.data.total_historical_runs || 0;
}

function renderTasks() {
  if (!state.data) return;
  const filter = state.taskFilter;
  const tasks = (state.data.tasks || []).filter(t => !filter || [t.fab_id,t.fab_description,t.thread_id,t.level_no,t.status].join(" ").toLowerCase().includes(filter));
  $("taskRows").innerHTML = tasks.map(t => {
    const duration = t.completed_at ? t.last_duration_seconds : t.current_duration_seconds;
    const average = t.completed_run_count >= 2 ? formatDuration(t.average_duration_seconds) : "--";
    const avgNote = t.completed_run_count ? `${t.completed_run_count} 次已完成` : "暂无完成记录";
    return `<tr>
      <td class="fab-cell"><strong>${esc(t.fab_id)}</strong><span title="${esc(t.fab_description)}">${esc(t.fab_description || "—")}</span></td>
      <td><span class="time-main">${esc(t.thread_id)}</span><span class="subtle">Level ${esc(t.level_no)} · ${esc(t.level_description || "")}</span></td>
      <td><span class="status-pill status-${esc(t.status)}">${esc(t.status || "?")}</span></td>
      <td><span class="time-main">${formatDateTime(t.act_time)}</span></td>
      <td><span class="time-main">${formatDateTime(t.started_at)}</span></td>
      <td><span class="duration">${duration ? formatDuration(duration) : "--"}</span></td>
      <td><span class="duration">${average}</span><span class="subtle">${avgNote}</span></td>
      <td><div class="anomaly-list">${formatAnomalies(t.anomaly_times)}</div></td>
    </tr>`;
  }).join("");
  $("taskEmpty").classList.toggle("hidden", tasks.length !== 0);
}

function renderHistory() {
  if (!state.data) return;
  const filter = state.historyFilter;
  const runs = (state.data.recent_runs || []).filter(r => !filter || [r.task.process_date,r.task.fab_id,r.task.thread_id,r.task.level_no].join(" ").toLowerCase().includes(filter));
  $("historyRows").innerHTML = runs.map(r => `<tr>
    <td>${esc(r.task.process_date)}</td><td class="fab-cell"><strong>${esc(r.task.fab_id)}</strong></td>
    <td>${esc(r.task.thread_id)}<span class="subtle">Level ${esc(r.task.level_no)}</span></td>
    <td>${formatDateTime(r.started_at)}</td><td>${formatDateTime(r.completed_at)}</td>
    <td><span class="duration">${r.completed_at ? formatDuration(r.duration_seconds) : "运行中"}</span></td>
    <td><div class="anomaly-list">${formatAnomalies(r.anomaly_times)}</div></td>
  </tr>`).join("");
  $("historyEmpty").classList.toggle("hidden", runs.length !== 0);
}

function showPanel(tab) {
  document.querySelectorAll(".tab").forEach(t => t.classList.toggle("active", t === tab));
  document.querySelectorAll(".panel").forEach(p => p.classList.toggle("active", p.id === tab.dataset.panel));
  if (tab.dataset.panel === "graphPanel") setTimeout(() => { fitGraph(); renderGraph(); }, 0);
}

function renderGraph() {
  if (!state.data) return;
  const nodes = state.data.graph_nodes || [], edges = state.data.graph_edges || [];
  const svg = $("dag");
  $("graphEmpty").classList.toggle("hidden", nodes.length !== 0);
  if (!nodes.length) { svg.innerHTML = ""; return; }

  const layout = dagLayout(nodes, edges), width = layout.width, height = layout.height;
  const marker = `<defs><marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto"><path d="M0,0 L0,6 L8,3 z" fill="#aeb9c5"/></marker></defs>`;
  const paths = edges.map(e => {
    const a = layout.positions[e.from], b = layout.positions[e.to]; if (!a || !b) return "";
    const x1=a.x+170,y1=a.y+39,x2=b.x,y2=b.y+39,m=(x1+x2)/2;
    return `<path class="dag-edge" marker-end="url(#arrow)" d="M${x1},${y1} C${m},${y1} ${m},${y2} ${x2-7},${y2}"/>`;
  }).join("");
  const groups = nodes.map(n => {
    const p=layout.positions[n.id], status=n.status||"W";
    const meta=n.started_at ? `开始 ${shortTime(n.started_at)}` : n.average_duration_seconds ? `平均 ${formatDuration(n.average_duration_seconds)}` : "依赖节点";
    const desc=(n.description||"").slice(0,22);
    return `<g class="dag-node ${n.current?"current":""} status-${esc(status)}" transform="translate(${p.x},${p.y})">
      <rect width="170" height="78" rx="10"/><circle cx="15" cy="18" r="4" fill="${statusColor(status)}"/>
      <text class="node-title" x="26" y="22">${esc(n.id)}</text><text class="node-status" x="150" y="22" text-anchor="end" fill="${statusColor(status)}">${esc(status)}</text>
      <text class="node-meta" x="15" y="44">${esc(meta)}</text><text class="node-meta" x="15" y="62">${esc(desc)}</text></g>`;
  }).join("");
  svg.setAttribute("viewBox", `0 0 ${Math.max(width,400)} ${Math.max(height,300)}`);
  svg.innerHTML = `${marker}<g id="graphScene" transform="translate(${state.graph.x} ${state.graph.y}) scale(${state.graph.scale})">${paths}${groups}</g>`;
}

function dagLayout(nodes, edges) {
  const incoming={}, outgoing={}; nodes.forEach(n=>{incoming[n.id]=[];outgoing[n.id]=[]});
  edges.forEach(e=>{if(incoming[e.to]&&outgoing[e.from]){incoming[e.to].push(e.from);outgoing[e.from].push(e.to)}});
  const degree={}; Object.keys(incoming).forEach(k=>degree[k]=incoming[k].length);
  const queue=Object.keys(degree).filter(k=>degree[k]===0).sort(), level={}; queue.forEach(k=>level[k]=0);
  while(queue.length){const id=queue.shift();outgoing[id].forEach(next=>{level[next]=Math.max(level[next]||0,(level[id]||0)+1);degree[next]--;if(degree[next]===0)queue.push(next)})}
  nodes.forEach(n=>{if(level[n.id]===undefined)level[n.id]=0});
  const columns={};nodes.forEach(n=>(columns[level[n.id]]??=[]).push(n.id));
  const positions={};let maxRows=1,maxLevel=0;Object.keys(columns).forEach(k=>{const l=+k;maxLevel=Math.max(maxLevel,l);columns[k].sort();maxRows=Math.max(maxRows,columns[k].length);columns[k].forEach((id,i)=>positions[id]={x:40+l*245,y:30+i*112})});
  return {positions,width:100+maxLevel*245+190,height:60+maxRows*112};
}

function setupGraphControls() {
  const viewport=$("graphViewport");
  viewport.addEventListener("wheel", e=>{e.preventDefault();state.graph.scale=Math.min(2.2,Math.max(.35,state.graph.scale*(e.deltaY<0?1.1:.9)));renderGraph()},{passive:false});
  viewport.addEventListener("mousedown",e=>{if(e.target.closest(".zoom-tools"))return;state.graph.dragging=true;state.graph.startX=e.clientX-state.graph.x;state.graph.startY=e.clientY-state.graph.y;viewport.classList.add("dragging")});
  window.addEventListener("mousemove",e=>{if(!state.graph.dragging)return;state.graph.x=e.clientX-state.graph.startX;state.graph.y=e.clientY-state.graph.startY;renderGraph()});
  window.addEventListener("mouseup",()=>{state.graph.dragging=false;viewport.classList.remove("dragging")});
  $("zoomIn").onclick=()=>{state.graph.scale=Math.min(2.2,state.graph.scale*1.2);renderGraph()};
  $("zoomOut").onclick=()=>{state.graph.scale=Math.max(.35,state.graph.scale/1.2);renderGraph()};
  $("zoomReset").onclick=()=>{fitGraph();renderGraph()};
}
function fitGraph(){state.graph={...state.graph,scale:1,x:0,y:0}}

function formatDateTime(value){if(!value)return "--";const d=new Date(value);if(Number.isNaN(d.getTime()))return "--";return new Intl.DateTimeFormat("zh-CN",{month:"2-digit",day:"2-digit",hour:"2-digit",minute:"2-digit",second:"2-digit",hour12:false}).format(d)}
function shortTime(v){return formatDateTime(v).replace(/^\d{2}-\d{2}\s*/,"")}
function formatDuration(seconds){seconds=Math.max(0,Math.floor(Number(seconds)||0));const d=Math.floor(seconds/86400),h=Math.floor(seconds%86400/3600),m=Math.floor(seconds%3600/60),s=seconds%60;return (d?`${d}天 `:"")+`${String(h).padStart(2,"0")}:${String(m).padStart(2,"0")}:${String(s).padStart(2,"0")}`}
function formatAnomalies(values){return values&&values.length?values.map(formatDateTime).join("<br>"):"—"}
function statusColor(status){return ({I:"#165dff",R:"#168f62",E:"#d83b3b",B:"#d47b00",W:"#8c98a4"})[status]||"#8c98a4"}
function esc(value){return String(value??"").replace(/[&<>'"]/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;","'":"&#39;",'"':"&quot;"})[c])}
let toastTimer;function showToast(message){clearTimeout(toastTimer);$("toast").textContent=message;$("toast").classList.remove("hidden");toastTimer=setTimeout(()=>$("toast").classList.add("hidden"),3000)}
