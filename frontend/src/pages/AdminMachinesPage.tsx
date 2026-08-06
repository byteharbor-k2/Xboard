import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useState, type FormEvent } from "react";

import {
  bindMachineNodes,
  deleteMachine,
  getMachineHistory,
  getMachineInstallCommand,
  getMachineNodes,
  getMachineToken,
  listMachineAssignableNodes,
  listMachines,
  rotateMachineToken,
  saveMachine,
  updateMachineNode,
  type MachineCredential,
  type MachineDraft,
  type MachineLoadHistory,
  type ManagedMachine
} from "../admin/machineManagementApi";
import { AdminShell } from "../components/AdminShell";
import { ApiError } from "../lib/http";
import { navigate } from "../lib/navigation";
import { useAdminAuthStore } from "../store/adminAuth";
import { useAdminPreferences } from "../store/adminPreferences";

type Language = "zh-CN" | "en-US";
type StatusFilter = "all" | "online" | "offline" | "high";
type NodeFilter = "all" | "with" | "without";
type HistoryMetric = "cpu" | "mem" | "disk" | "netIn" | "netOut";

const copy = {
  "zh-CN": {
    eyebrow: "节点管理",
    title: "服务器管理",
    description: "用于查看服务器健康、负载与承载节点，并从运维视角快捷发起节点操作。",
    create: "添加服务器",
    total: "服务器总数",
    onlineTotal: "在线服务器",
    offlineTotal: "离线/失联",
    highTotal: "高负载",
    nodeTotal: "节点数",
    searchPlaceholder: "搜索服务器名称、备注或 SID...",
    statusFilter: "状态",
    nodeFilter: "节点",
    all: "全部",
    online: "在线",
    offline: "离线",
    highLoad: "高负载",
    enabled: "已启用",
    disabled: "已禁用",
    withNodes: "有节点",
    withoutNodes: "无节点",
    summaryHint: "适合集中查看服务器在线情况、承载节点数量与资源压力。",
    name: "服务器名称",
    status: "状态",
    load: "负载",
    nodes: "节点数",
    lastSeen: "最后心跳",
    actions: "操作",
    neverConnected: "尚未连接",
    noReport: "暂无上报",
    report: "负载上报",
    carriedNodes: "已承载节点",
    details: "服务器详情",
    edit: "编辑服务器",
    remove: "删除",
    removeConfirm: "删除服务器后，其绑定节点将自动解除关联。确定继续吗？",
    loading: "正在加载服务器…",
    empty: "没有符合当前条件的服务器。",
    createTitle: "新建服务器",
    createHint: "当你希望一台服务器承载多个节点时，再创建服务器记录。",
    editTitle: "编辑服务器",
    machineName: "服务器名称",
    namePlaceholder: "例如 HK-01",
    notes: "备注",
    notesPlaceholder: "关于此服务器的可选备注",
    enableMachine: "启用服务器",
    enableHint: "禁用后 xboard-node 将不再使用此服务器。",
    cancel: "取消",
    submit: "提交",
    tokenTitle: "机器部署信息",
    tokenHint: "Token 用于 xboard-node 机器认证，请妥善保存。",
    token: "Token",
    install: "安装 xboard-node",
    installHint: "在目标服务器上执行此命令，即可用 machine mode 安装 xboard-node 并接入当前服务器记录。",
    installFootnote: "需要 root 或 sudo 权限，且目标服务器需为支持 systemd 的 Linux。",
    command: "安装命令",
    copy: "复制",
    copied: "已复制",
    close: "关闭",
    currentLoad: "负载",
    trend: "负载趋势",
    memory: "内存",
    disk: "磁盘",
    network: "网络速率",
    serverToken: "服务器 Token",
    showToken: "查看 Token",
    hideToken: "隐藏 Token",
    rotate: "重置 Token",
    rotateConfirm: "重置后旧 Token 会立即失效，且需要在服务器更新 Token。确定继续吗？",
    linkedNodes: "关联节点",
    activeNodes: "个已激活",
    associate: "关联已有节点",
    goNodes: "前往节点管理",
    createNode: "新增节点到此服务器",
    type: "类型",
    address: "地址",
    active: "已激活",
    detach: "取消关联",
    detachConfirm: "确定取消该节点与服务器的关联吗？",
    noNodes: "这台服务器还没有关联节点。",
    associateTitle: "关联已有节点",
    associateHint: "选择要关联到",
    searchNodes: "搜索节点名称、地址、类型...",
    noAssignable: "没有未绑定的节点",
    selected: "已选",
    bind: "关联",
    noHistory: "当前时段暂无负载上报记录",
    operationFailed: "操作失败",
    resetFilters: "重置筛选", perPage: "每页显示", items: "共 {count} 项", page: "第 {page} / {pages} 页",
    first: "首页", previous: "上一页", next: "下一页", last: "末页", selectAll: "全选", clearAll: "取消全选",
    tokenAutoHide: "Token 将在 30 秒后自动隐藏。"
  },
  "en-US": {
    eyebrow: "Infrastructure",
    title: "Machines",
    description: "Review machine health, load, hosted nodes, and launch common node operations.",
    create: "Add machine",
    total: "Machines",
    onlineTotal: "Online",
    offlineTotal: "Offline",
    highTotal: "High load",
    nodeTotal: "Nodes",
    searchPlaceholder: "Search name, notes, or SID...",
    statusFilter: "Status",
    nodeFilter: "Nodes",
    all: "All",
    online: "Online",
    offline: "Offline",
    highLoad: "High load",
    enabled: "Enabled",
    disabled: "Disabled",
    withNodes: "With nodes",
    withoutNodes: "Without nodes",
    summaryHint: "A consolidated view of connectivity, hosted nodes, and resource pressure.",
    name: "Machine",
    status: "Status",
    load: "Load",
    nodes: "Nodes",
    lastSeen: "Heartbeat",
    actions: "Actions",
    neverConnected: "Never connected",
    noReport: "No report",
    report: "Load report",
    carriedNodes: "hosted nodes",
    details: "Details",
    edit: "Edit",
    remove: "Delete",
    removeConfirm: "Deleting this machine detaches its nodes. Continue?",
    loading: "Loading machines…",
    empty: "No machines match the current filters.",
    createTitle: "New machine",
    createHint: "Create a machine record when one server will host multiple nodes.",
    editTitle: "Edit machine",
    machineName: "Machine name",
    namePlaceholder: "e.g. HK-01",
    notes: "Notes",
    notesPlaceholder: "Optional notes about this machine",
    enableMachine: "Enable machine",
    enableHint: "When disabled, xboard-node can no longer use this machine.",
    cancel: "Cancel",
    submit: "Submit",
    tokenTitle: "Machine deployment",
    tokenHint: "This token authenticates xboard-node. Store it securely.",
    token: "Token",
    install: "Install xboard-node",
    installHint: "Run this command on the target server to install xboard-node in machine mode.",
    installFootnote: "Requires root or sudo on a Linux server with systemd.",
    command: "Install command",
    copy: "Copy",
    copied: "Copied",
    close: "Close",
    currentLoad: "Load",
    trend: "Load trend",
    memory: "Memory",
    disk: "Disk",
    network: "Network",
    serverToken: "Machine token",
    showToken: "Show token",
    hideToken: "Hide token",
    rotate: "Reset token",
    rotateConfirm: "The old token becomes invalid immediately and must be updated on the server. Continue?",
    linkedNodes: "Linked nodes",
    activeNodes: "active",
    associate: "Link existing nodes",
    goNodes: "Open node management",
    createNode: "Add node to this machine",
    type: "Type",
    address: "Address",
    active: "Active",
    detach: "Detach",
    detachConfirm: "Detach this node from the machine?",
    noNodes: "No nodes are linked to this machine.",
    associateTitle: "Link existing nodes",
    associateHint: "Select nodes to link to",
    searchNodes: "Search node name, address, or type...",
    noAssignable: "No unbound nodes",
    selected: "Selected",
    bind: "Link",
    noHistory: "No load reports in this period",
    operationFailed: "Operation failed",
    resetFilters: "Reset filters", perPage: "Per page", items: "{count} items", page: "Page {page} of {pages}",
    first: "First", previous: "Previous", next: "Next", last: "Last", selectAll: "Select all", clearAll: "Clear all",
    tokenAutoHide: "The token will hide automatically after 30 seconds."
  }
} as const;

function secondsNow() {
  return Math.floor(Date.now() / 1000);
}

function isOnline(machine: ManagedMachine) {
  return machine.is_active && machine.last_seen_at !== null && secondsNow() - machine.last_seen_at <= 180;
}

function percent(used: number, total: number) {
  return total > 0 ? Math.min(100, Math.max(0, (used / total) * 100)) : 0;
}

function isHighLoad(machine: ManagedMachine) {
  const load = machine.load_status;
  return Boolean(load && (
    load.cpu >= 85 || percent(load.mem.used, load.mem.total) >= 90 || percent(load.disk.used, load.disk.total) >= 90
  ));
}

function formatPercent(value: number) {
  return `${Math.round(Math.min(100, Math.max(0, value)))}%`;
}

function formatBytes(value: number) {
  if (!Number.isFinite(value) || value <= 0) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1);
  const amount = value / 1024 ** index;
  return `${amount.toFixed(amount >= 100 || index === 0 ? 0 : amount >= 10 ? 1 : 2)} ${units[index]}`;
}

function relativeTime(value: number | null, language: Language, fallback: string) {
  if (!value) return fallback;
  const seconds = Math.max(0, secondsNow() - value);
  if (seconds < 60) return `${seconds}s`;
  if (seconds < 3_600) return `${Math.floor(seconds / 60)}m`;
  if (seconds < 86_400) return `${Math.floor(seconds / 3_600)}h`;
  const days = Math.floor(seconds / 86_400);
  return language === "zh-CN" ? `${days}天` : `${days}d`;
}

function errorText(error: unknown, fallback: string) {
  return error instanceof ApiError ? error.message : fallback;
}

function historyValue(item: MachineLoadHistory, metric: HistoryMetric) {
  if (metric === "cpu") return item.cpu;
  if (metric === "mem") return percent(item.mem_used, item.mem_total);
  if (metric === "disk") return percent(item.disk_used, item.disk_total);
  if (metric === "netIn") return item.net_in_speed ?? 0;
  return item.net_out_speed ?? 0;
}

function LoadBar({ label, value, detail }: { label: string; value: number; detail?: string }) {
  return <div className="machine-load-row">
    <span>{label}</span><strong>{detail ?? formatPercent(value)}</strong>
    <i><b style={{ width: `${Math.min(100, Math.max(0, value))}%` }} /></i>
  </div>;
}

function HistoryChart({
  items,
  metric,
  empty
}: {
  items: MachineLoadHistory[];
  metric: HistoryMetric;
  empty: string;
}) {
  if (!items.length) return <p className="machine-chart-empty">{empty}</p>;
  const values = items.map((item) => historyValue(item, metric));
  const maximum = metric === "cpu" || metric === "mem" || metric === "disk"
    ? 100
    : Math.max(...values, 1);
  const points = values.map((value, index) => {
    const x = values.length === 1 ? 50 : (index / (values.length - 1)) * 100;
    const y = 96 - Math.min(94, (value / maximum) * 88);
    return `${x},${y}`;
  }).join(" ");
  return <div className="machine-chart">
    <svg aria-label="load trend" preserveAspectRatio="none" viewBox="0 0 100 100">
      <line x1="0" x2="100" y1="25" y2="25" />
      <line x1="0" x2="100" y1="50" y2="50" />
      <line x1="0" x2="100" y1="75" y2="75" />
      <polyline points={points} />
    </svg>
    <div><span>{new Date(items[0].recorded_at * 1000).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</span>
      <strong>{metric.startsWith("net") ? `${formatBytes(values.at(-1) ?? 0)}/s` : formatPercent(values.at(-1) ?? 0)}</strong>
      <span>{new Date(items.at(-1)!.recorded_at * 1000).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</span></div>
  </div>;
}

export function AdminMachinesPage() {
  const language = useAdminPreferences((state) => state.language);
  const accessToken = useAdminAuthStore((state) => state.accessToken)!;
  const queryClient = useQueryClient();
  const text = copy[language];

  const [search, setSearch] = useState("");
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("all");
  const [nodeFilter, setNodeFilter] = useState<NodeFilter>("all");
  const [pageSize, setPageSize] = useState(10);
  const [page, setPage] = useState(1);
  const [editing, setEditing] = useState<ManagedMachine | null | undefined>();
  const [draft, setDraft] = useState<MachineDraft>({ name: "", notes: "", is_active: true });
  const [credential, setCredential] = useState<MachineCredential>();
  const [detailMachineId, setDetailMachineId] = useState<number>();
  const [historyRange, setHistoryRange] = useState(6);
  const [historyMetric, setHistoryMetric] = useState<HistoryMetric>("cpu");
  const [tokenVisible, setTokenVisible] = useState(false);
  const [revealedToken, setRevealedToken] = useState<string>();
  const [associateOpen, setAssociateOpen] = useState(false);
  const [associateSearch, setAssociateSearch] = useState("");
  const [selectedNodeIds, setSelectedNodeIds] = useState<number[]>([]);
  const [copiedCredential, setCopiedCredential] = useState<"token" | "command">();
  const [error, setError] = useState("");

  const machinesQuery = useQuery({
    queryKey: ["admin", "machines"],
    queryFn: () => listMachines(accessToken),
    refetchInterval: 30_000
  });
  const detailMachine = machinesQuery.data?.find((machine) => machine.id === detailMachineId);
  const historyQuery = useQuery({
    queryKey: ["admin", "machines", detailMachineId, "history", historyRange],
    queryFn: () => getMachineHistory(accessToken, detailMachineId!, historyRange),
    enabled: detailMachineId !== undefined
  });
  const machineNodesQuery = useQuery({
    queryKey: ["admin", "machines", detailMachineId, "nodes"],
    queryFn: () => getMachineNodes(accessToken, detailMachineId!),
    enabled: detailMachineId !== undefined
  });
  const installQuery = useQuery({
    queryKey: ["admin", "machines", detailMachineId, "install-command"],
    queryFn: () => getMachineInstallCommand(accessToken, detailMachineId!),
    enabled: detailMachineId !== undefined
  });
  const assignableNodesQuery = useQuery({
    queryKey: ["admin", "nodes", "assignable"],
    queryFn: () => listMachineAssignableNodes(accessToken),
    enabled: associateOpen
  });

  const machines = machinesQuery.data ?? [];
  const summary = useMemo(() => ({
    total: machines.length,
    online: machines.filter(isOnline).length,
    offline: machines.filter((machine) => !isOnline(machine)).length,
    high: machines.filter(isHighLoad).length,
    nodes: machines.reduce((total, machine) => total + machine.servers_count, 0)
  }), [machines]);
  const filteredMachines = useMemo(() => {
    const keyword = search.trim().toLowerCase();
    return machines.filter((machine) => {
      const matchesSearch = !keyword || machine.name.toLowerCase().includes(keyword)
        || (machine.notes ?? "").toLowerCase().includes(keyword)
        || String(machine.id).includes(keyword);
      const matchesStatus = statusFilter === "all"
        || statusFilter === "online" && isOnline(machine)
        || statusFilter === "offline" && !isOnline(machine)
        || statusFilter === "high" && isHighLoad(machine);
      const matchesNodes = nodeFilter === "all"
        || nodeFilter === "with" && machine.servers_count > 0
        || nodeFilter === "without" && machine.servers_count === 0;
      return matchesSearch && matchesStatus && matchesNodes;
    });
  }, [machines, nodeFilter, search, statusFilter]);
  const pageCount = Math.max(1, Math.ceil(filteredMachines.length / pageSize));
  const currentPage = Math.min(page, pageCount);
  const visibleMachines = filteredMachines.slice((currentPage - 1) * pageSize, currentPage * pageSize);
  const unboundNodes = useMemo(() => {
    const keyword = associateSearch.trim().toLowerCase();
    return (assignableNodesQuery.data ?? []).filter((node) => node.machine_id === null && (
      !keyword || node.name.toLowerCase().includes(keyword) || node.type.toLowerCase().includes(keyword)
      || (node.host ?? "").toLowerCase().includes(keyword)
    ));
  }, [assignableNodesQuery.data, associateSearch]);
  const allAssignableSelected = unboundNodes.length > 0 && unboundNodes.every((node) => selectedNodeIds.includes(node.id));

  useEffect(() => setPage(1), [search, statusFilter, nodeFilter, pageSize]);
  useEffect(() => {
    if (!tokenVisible) return;
    const timer = window.setTimeout(() => {
      setTokenVisible(false);
      setRevealedToken(undefined);
    }, 30_000);
    return () => window.clearTimeout(timer);
  }, [detailMachineId, revealedToken, tokenVisible]);

  async function refreshMachineData() {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["admin", "machines"] }),
      queryClient.invalidateQueries({ queryKey: ["admin", "nodes"] }),
      queryClient.invalidateQueries({ queryKey: ["admin", "machines", detailMachineId, "nodes"] })
    ]);
  }

  const saveMutation = useMutation({
    mutationFn: () => saveMachine(accessToken, draft),
    onSuccess: async (result) => {
      await queryClient.invalidateQueries({ queryKey: ["admin", "machines"] });
      setEditing(undefined);
      if (result !== true) setCredential(result);
    }
  });
  const deleteMutation = useMutation({
    mutationFn: (id: number) => deleteMachine(accessToken, id),
    onSuccess: async (_, id) => {
      if (detailMachineId === id) setDetailMachineId(undefined);
      await refreshMachineData();
    },
    onError: (caught) => setError(errorText(caught, text.operationFailed))
  });
  const nodeMutation = useMutation({
    mutationFn: ({ id, values }: { id: number; values: { enabled?: boolean; machine_id?: number | null } }) =>
      updateMachineNode(accessToken, id, values),
    onSuccess: refreshMachineData,
    onError: (caught) => setError(errorText(caught, text.operationFailed))
  });
  const associateMutation = useMutation({
    mutationFn: () => bindMachineNodes(accessToken, detailMachineId!, selectedNodeIds),
    onSuccess: async () => {
      setAssociateOpen(false);
      setAssociateSearch("");
      setSelectedNodeIds([]);
      await refreshMachineData();
    },
    onError: (caught) => setError(errorText(caught, text.operationFailed))
  });

  function openCreate() {
    setDraft({ name: "", notes: "", is_active: true });
    setEditing(null);
    setError("");
  }

  function openEdit(machine: ManagedMachine) {
    setDraft({ id: machine.id, name: machine.name, notes: machine.notes ?? "", is_active: machine.is_active });
    setEditing(machine);
    setError("");
  }

  function openDetails(machine: ManagedMachine) {
    setDetailMachineId(machine.id);
    setHistoryRange(6);
    setHistoryMetric("cpu");
    setTokenVisible(false);
    setRevealedToken(undefined);
    setError("");
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError("");
    try { await saveMutation.mutateAsync(); }
    catch (caught) { setError(errorText(caught, text.operationFailed)); }
  }

  async function showToken() {
    if (tokenVisible) { setTokenVisible(false); return; }
    try {
      if (!revealedToken) setRevealedToken((await getMachineToken(accessToken, detailMachineId!)).token);
      setTokenVisible(true);
    } catch (caught) { setError(errorText(caught, text.operationFailed)); }
  }

  async function rotateToken() {
    if (!detailMachineId || !window.confirm(text.rotateConfirm)) return;
    try {
      const result = await rotateMachineToken(accessToken, detailMachineId);
      setRevealedToken(result.token);
      setTokenVisible(true);
      await queryClient.invalidateQueries({ queryKey: ["admin", "machines", detailMachineId, "install-command"] });
    } catch (caught) { setError(errorText(caught, text.operationFailed)); }
  }

  async function copyCredentialValue(value: string, field: "token" | "command") {
    try { await navigator.clipboard.writeText(value); }
    catch {
      const input = document.createElement("textarea");
      input.value = value; input.style.position = "fixed"; input.style.opacity = "0";
      document.body.appendChild(input); input.select(); document.execCommand("copy"); input.remove();
    }
    setCopiedCredential(field);
    window.setTimeout(() => setCopiedCredential((current) => current === field ? undefined : current), 1_500);
  }

  function goToNodes(create = false) {
    if (!detailMachineId) return;
    navigate(`/admin/nodes?machine_id=${detailMachineId}${create ? "&open_create=1" : ""}`);
  }

  const linkedNodes = machineNodesQuery.data ?? [];
  const currentLoad = detailMachine?.load_status;

  return <AdminShell>
    <style>{machineStyles}</style>
    <div className="machine-management-v2">
      <header className="admin-page-heading admin-page-heading-action">
        <div><p>{text.eyebrow}</p><h1>{text.title}</h1><span>{text.description}</span></div>
        <button className="admin-primary-button" onClick={openCreate} type="button">+ {text.create}</button>
      </header>

      <section className="machine-summary-grid" aria-label={text.title}>
        {[
          [text.total, summary.total, "S"], [text.onlineTotal, summary.online, "✓"],
          [text.offlineTotal, summary.offline, "!"], [text.highTotal, summary.high, "↗"],
          [text.nodeTotal, summary.nodes, "N"]
        ].map(([label, value, icon]) => <article className="admin-metric-card" key={String(label)}>
          <div><span>{label}</span><i>{icon}</i></div><strong>{value}</strong>
        </article>)}
      </section>

      <section className="admin-card machine-overview-card">
        <div className="machine-filter-bar">
          <input aria-label={text.searchPlaceholder} placeholder={text.searchPlaceholder} value={search}
            onChange={(event) => setSearch(event.target.value)} />
          <select aria-label={text.statusFilter} value={statusFilter} onChange={(event) => setStatusFilter(event.target.value as StatusFilter)}>
            <option value="all">{text.statusFilter} · {text.all}</option><option value="online">{text.online}</option>
            <option value="offline">{text.offline}</option><option value="high">{text.highLoad}</option>
          </select>
          <select aria-label={text.nodeFilter} value={nodeFilter} onChange={(event) => setNodeFilter(event.target.value as NodeFilter)}>
            <option value="all">{text.nodeFilter} · {text.all}</option><option value="with">{text.withNodes}</option>
            <option value="without">{text.withoutNodes}</option>
          </select>
          <button disabled={!search && statusFilter === "all" && nodeFilter === "all"} onClick={() => { setSearch(""); setStatusFilter("all"); setNodeFilter("all"); setPage(1); }} type="button">{text.resetFilters}</button>
        </div>
        <div className="machine-overview-note"><div><span>{text.online}: {summary.online}/{summary.total}</span>
          <span>{text.highLoad}: {summary.high}</span></div><p>{text.summaryHint}</p></div>
        {error && <p className="admin-operation-error">{error}</p>}
        <div className="admin-table-wrap machine-table-wrap">
          {machinesQuery.isPending ? <p className="admin-table-empty">{text.loading}</p>
            : !filteredMachines.length ? <p className="admin-table-empty">{text.empty}</p>
              : <table className="admin-table machine-table machine-health-table"><thead><tr>
                <th>{text.name}</th><th>{text.status}</th><th>{text.load}</th><th>{text.nodes}</th><th>{text.lastSeen}</th><th>{text.actions}</th>
              </tr></thead><tbody>{visibleMachines.map((machine) => {
                const load = machine.load_status;
                const online = isOnline(machine);
                const high = isHighLoad(machine);
                return <tr key={machine.id}>
                  <td><div className="machine-name-line"><strong>{machine.name}</strong><small>SID:{machine.id}</small>
                    {high && <em>{text.highLoad}</em>}</div><div className="machine-row-meta"><span>{online ? text.online : text.offline}</span>
                      <b>•</b><span>{text.lastSeen}: {relativeTime(machine.last_seen_at, language, text.neverConnected)}</span>
                      <b>•</b><span>{text.nodes}: {machine.servers_count}</span></div>{machine.notes && <p>{machine.notes}</p>}</td>
                  <td><span className={`admin-state-chip ${online ? "success" : ""}`}>{!machine.is_active ? text.disabled : online ? text.online : text.offline}</span></td>
                  <td>{load ? <div className="machine-row-load"><LoadBar label="CPU" value={load.cpu} />
                    <LoadBar label="MEM" value={percent(load.mem.used, load.mem.total)} />
                    <LoadBar label="DISK" value={percent(load.disk.used, load.disk.total)} detail={`${formatBytes(load.disk.used)} / ${formatBytes(load.disk.total)}`} /></div> : "—"}</td>
                  <td><button className="machine-node-count" onClick={() => openDetails(machine)} type="button"><strong>{machine.servers_count}</strong><span>{text.carriedNodes}</span></button></td>
                  <td><strong>{relativeTime(machine.last_seen_at, language, text.neverConnected)}</strong><small>{text.report}: {relativeTime(load?.updated_at ?? null, language, text.noReport)}</small></td>
                  <td><div className="machine-row-actions"><button aria-label={text.details} onClick={() => openDetails(machine)} title={text.details} type="button">⌁</button>
                    <button aria-label={text.edit} onClick={() => openEdit(machine)} title={text.edit} type="button">✎</button>
                    <button aria-label={text.remove} className="danger" onClick={() => window.confirm(text.removeConfirm) && deleteMutation.mutate(machine.id)} title={text.remove} type="button">⌫</button></div></td>
                </tr>;
              })}</tbody></table>}
        </div>
        {filteredMachines.length > 0 && <div className="admin-pagination machine-pagination"><span>{text.items.replace("{count}", String(filteredMachines.length))}</span><label>{text.perPage}<select value={pageSize} onChange={(event) => setPageSize(Number(event.target.value))}>{[10, 20, 50].map((size) => <option key={size} value={size}>{size}</option>)}</select></label><span>{text.page.replace("{page}", String(currentPage)).replace("{pages}", String(pageCount))}</span><div><button aria-label={text.first} disabled={currentPage === 1} onClick={() => setPage(1)} type="button">«</button><button aria-label={text.previous} disabled={currentPage === 1} onClick={() => setPage(currentPage - 1)} type="button">‹</button><button aria-label={text.next} disabled={currentPage === pageCount} onClick={() => setPage(currentPage + 1)} type="button">›</button><button aria-label={text.last} disabled={currentPage === pageCount} onClick={() => setPage(pageCount)} type="button">»</button></div></div>}
      </section>

      {editing !== undefined && <div className="admin-modal-backdrop" role="presentation">
        <form className="admin-modal machine-modal" onSubmit={(event) => void submit(event)}>
          <header><div><h2>{editing ? text.editTitle : text.createTitle}</h2>{!editing && <p>{text.createHint}</p>}</div>
            <button aria-label={text.close} onClick={() => setEditing(undefined)} type="button">×</button></header>
          <div className="machine-form"><label>{text.machineName}<input autoFocus required maxLength={255} placeholder={text.namePlaceholder} value={draft.name}
            onChange={(event) => setDraft({ ...draft, name: event.target.value })} /></label>
            <label>{text.notes}<textarea placeholder={text.notesPlaceholder} value={draft.notes}
              onChange={(event) => setDraft({ ...draft, notes: event.target.value })} /></label>
            <label className="machine-check"><input checked={draft.is_active} type="checkbox"
              onChange={(event) => setDraft({ ...draft, is_active: event.target.checked })} /><span><strong>{text.enableMachine}</strong><small>{text.enableHint}</small></span></label>
            {error && <p className="admin-operation-error">{error}</p>}</div>
          <footer><button onClick={() => setEditing(undefined)} type="button">{text.cancel}</button>
            <button className="primary" disabled={saveMutation.isPending} type="submit">{text.submit}</button></footer>
        </form></div>}

      {credential && <div className="admin-modal-backdrop" role="presentation"><section className="admin-modal machine-modal">
        <header><h2>{text.tokenTitle}</h2><button aria-label={text.close} onClick={() => setCredential(undefined)} type="button">×</button></header>
        <div className="machine-credential"><p>{text.tokenHint}</p>
          <label>{text.token}<span className="machine-credential-value"><code>{credential.token}</code>
            <button aria-label={`${text.copy} ${text.token}`} className={copiedCredential === "token" ? "copied" : ""}
              onClick={() => void copyCredentialValue(credential.token, "token")} title={copiedCredential === "token" ? text.copied : text.copy} type="button">{copiedCredential === "token" ? "✓" : "⧉"}</button></span></label>
          {credential.install_command && <label>{text.command}<span className="machine-credential-value"><code>{credential.install_command}</code>
            <button aria-label={`${text.copy} ${text.command}`} className={copiedCredential === "command" ? "copied" : ""}
              onClick={() => void copyCredentialValue(credential.install_command!, "command")} title={copiedCredential === "command" ? text.copied : text.copy} type="button">{copiedCredential === "command" ? "✓" : "⧉"}</button></span></label>}
        </div><footer><button className="primary" onClick={() => setCredential(undefined)} type="button">{text.close}</button></footer>
      </section></div>}

      {detailMachine && <div className="admin-modal-backdrop machine-detail-backdrop" role="presentation">
        <section aria-label={detailMachine.name} className="admin-modal machine-detail-modal">
          <header><div className="machine-detail-heading"><div><h2>{detailMachine.name}</h2><span>SID:{detailMachine.id}</span>
            <em className={isOnline(detailMachine) ? "online" : ""}>{isOnline(detailMachine) ? text.online : text.offline}</em>
            {isHighLoad(detailMachine) && <em className="warning">{text.highLoad}</em>}</div>
            <p>{text.lastSeen}: {relativeTime(detailMachine.last_seen_at, language, text.neverConnected)} · {text.nodes}: {detailMachine.servers_count}</p>
            {detailMachine.notes && <small>{detailMachine.notes}</small>}</div>
            <button aria-label={text.close} onClick={() => { setDetailMachineId(undefined); setTokenVisible(false); setRevealedToken(undefined); }} type="button">×</button></header>
          <div className="machine-detail-body">
            <div className="machine-detail-quick"><button className="primary" onClick={() => goToNodes(true)} type="button">+ {text.createNode}</button>
              <button onClick={() => goToNodes()} type="button">{text.goNodes} →</button></div>
            <section className="machine-detail-section"><div className="machine-section-title"><h3>{text.trend}</h3>
              <div className="machine-history-controls"><span>{[1, 6, 12, 24].map((range) => <button className={historyRange === range ? "active" : ""} key={range} onClick={() => setHistoryRange(range)} type="button">{range}h</button>)}</span>
                <span>{(["cpu", "mem", "disk", "netIn", "netOut"] as HistoryMetric[]).map((metric) => <button className={historyMetric === metric ? "active" : ""} key={metric}
                  onClick={() => setHistoryMetric(metric)} type="button">{metric === "netIn" ? "↓ IN" : metric === "netOut" ? "↑ OUT" : metric.toUpperCase()}</button>)}</span></div></div>
              <HistoryChart empty={text.noHistory} items={historyQuery.data ?? []} metric={historyMetric} />
            </section>
            <section className="machine-detail-section"><h3>{text.currentLoad}</h3>{currentLoad ? <div className="machine-current-load">
              <LoadBar label="CPU" value={currentLoad.cpu} detail={`${currentLoad.cpu.toFixed(1)}%`} />
              <LoadBar label={text.memory} value={percent(currentLoad.mem.used, currentLoad.mem.total)} detail={`${formatBytes(currentLoad.mem.used)} / ${formatBytes(currentLoad.mem.total)}`} />
              <LoadBar label={text.disk} value={percent(currentLoad.disk.used, currentLoad.disk.total)} detail={`${formatBytes(currentLoad.disk.used)} / ${formatBytes(currentLoad.disk.total)}`} />
              <div className="machine-network"><span>{text.network}</span><strong>↓{formatBytes(currentLoad.net?.in_speed ?? 0)}/s ↑{formatBytes(currentLoad.net?.out_speed ?? 0)}/s</strong></div>
            </div> : <p className="machine-chart-empty">{text.noReport}</p>}</section>
            <section className="machine-detail-section"><h3>{text.serverToken}</h3><p>{text.tokenHint}</p>
              <div className="machine-token-actions"><button onClick={() => void showToken()} type="button">{tokenVisible ? text.hideToken : text.showToken}</button>
                <button onClick={() => void rotateToken()} type="button">{text.rotate}</button></div>
              {tokenVisible && revealedToken && <><span className="machine-credential-value"><code>{revealedToken}</code><button aria-label={`${text.copy} ${text.token}`}
                className={copiedCredential === "token" ? "copied" : ""} onClick={() => void copyCredentialValue(revealedToken, "token")} type="button">{copiedCredential === "token" ? "✓" : "⧉"}</button></span><small>{text.tokenAutoHide}</small></>}
            </section>
            <section className="machine-detail-section"><h3>{text.install}</h3><p>{text.installHint}</p>
              {installQuery.data?.command && <span className="machine-credential-value"><code>{installQuery.data.command}</code><button aria-label={`${text.copy} ${text.command}`}
                className={copiedCredential === "command" ? "copied" : ""} onClick={() => void copyCredentialValue(installQuery.data.command, "command")} type="button">{copiedCredential === "command" ? "✓" : "⧉"}</button></span>}
              <small>{text.installFootnote}</small>
            </section>
            <section className="machine-detail-section"><div className="machine-section-title"><div><h3>{text.linkedNodes}</h3>
              <p>{linkedNodes.length} {text.nodes} · {linkedNodes.filter((node) => node.enabled).length} {text.activeNodes}</p></div>
              <div><button onClick={() => { setAssociateOpen(true); setAssociateSearch(""); setSelectedNodeIds([]); }} type="button">{text.associate}</button>
                <button onClick={() => goToNodes()} type="button">{text.goNodes}</button></div></div>
              <div className="admin-table-wrap machine-linked-table">{machineNodesQuery.isPending ? <p className="admin-table-empty">{text.loading}</p>
                : !linkedNodes.length ? <p className="admin-table-empty">{text.noNodes}</p>
                  : <table className="admin-table"><thead><tr><th>{text.name}</th><th>{text.type}</th><th>{text.address}</th><th>{text.active}</th><th /></tr></thead>
                    <tbody>{linkedNodes.map((node) => <tr key={node.id}><td><button className="machine-node-link" onClick={() => goToNodes()} type="button">#{node.id} {node.name}</button></td>
                      <td>{node.type}</td><td>{node.host ?? "—"}:{node.port ?? node.server_port}</td><td><label className="machine-switch"><input checked={node.enabled} disabled={nodeMutation.isPending} type="checkbox"
                        onChange={() => nodeMutation.mutate({ id: node.id, values: { enabled: !node.enabled } })} /><span /></label></td>
                      <td><button className="machine-detach" onClick={() => window.confirm(text.detachConfirm) && nodeMutation.mutate({ id: node.id, values: { machine_id: null } })} type="button">{text.detach}</button></td></tr>)}</tbody></table>}
              </div>
            </section>
          </div>
        </section>
      </div>}

      {associateOpen && detailMachine && <div className="admin-modal-backdrop machine-associate-backdrop" role="presentation"><section aria-label={text.associateTitle} className="admin-modal machine-associate-modal">
        <header><div><h2>{text.associateTitle}</h2><p>{text.associateHint}「{detailMachine.name}」</p></div><button aria-label={text.close} onClick={() => setAssociateOpen(false)} type="button">×</button></header>
        <div className="machine-associate-body"><input autoFocus aria-label={text.searchNodes} placeholder={text.searchNodes} value={associateSearch} onChange={(event) => setAssociateSearch(event.target.value)} />
          <div className="machine-associate-selectall"><button disabled={!unboundNodes.length} onClick={() => setSelectedNodeIds((current) => allAssignableSelected ? current.filter((id) => !unboundNodes.some((node) => node.id === id)) : Array.from(new Set([...current, ...unboundNodes.map((node) => node.id)])))} type="button">{allAssignableSelected ? text.clearAll : text.selectAll}</button><span>{text.selected} {selectedNodeIds.length}</span></div>
          <div className="machine-associate-list">{!unboundNodes.length ? <p>{text.noAssignable}</p> : unboundNodes.map((node) => <label key={node.id}>
            <input checked={selectedNodeIds.includes(node.id)} type="checkbox" onChange={(event) => setSelectedNodeIds((current) => event.target.checked ? [...current, node.id] : current.filter((id) => id !== node.id))} />
            <span><strong>#{node.id} {node.name}</strong><small>{node.type} · {node.host ?? "—"}:{node.port ?? node.server_port}</small></span></label>)}</div>
        </div><footer><span>{text.selected} {selectedNodeIds.length}</span><button onClick={() => setAssociateOpen(false)} type="button">{text.cancel}</button>
          <button className="primary" disabled={!selectedNodeIds.length || associateMutation.isPending} onClick={() => associateMutation.mutate()} type="button">{text.bind} {selectedNodeIds.length} {text.nodes}</button></footer>
      </section></div>}
    </div>
  </AdminShell>;
}

const machineStyles = `
.machine-summary-grid{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:14px;margin-bottom:16px}.machine-summary-grid .admin-metric-card{min-height:104px}.machine-overview-card{padding:0 0 18px}.machine-filter-bar{display:grid;grid-template-columns:minmax(260px,1fr) 150px 150px auto;gap:10px;padding:20px 22px 12px}.machine-filter-bar input,.machine-filter-bar select,.machine-associate-body>input{width:100%;padding:10px 12px;border:1px solid #dfe5ef;border-radius:10px;background:#fff;color:#27334a;font:inherit}.machine-filter-bar>button{padding:0 13px;border:1px solid #dfe5ef;border-radius:10px;background:#fff;color:#3551a8;font:inherit;font-weight:750}.machine-filter-bar>button:disabled{opacity:.45}.machine-overview-note{display:flex;align-items:center;justify-content:space-between;padding:0 22px 13px;color:#758097;font-size:11px}.machine-overview-note div{display:flex;gap:7px}.machine-overview-note span{padding:5px 8px;border-radius:999px;background:#f0f4fb;font-weight:750}.machine-overview-note p{margin:0}.machine-table-wrap{margin-top:0}.machine-pagination{padding:14px 22px 0;border-top:1px solid #e7ebf2}.machine-health-table td{vertical-align:middle}.machine-health-table td:first-child{min-width:245px}.machine-name-line,.machine-row-meta{display:flex;align-items:center;gap:7px;flex-wrap:wrap}.machine-name-line strong{font-size:12px}.machine-name-line small{margin:0!important;padding:3px 6px;border-radius:999px;background:#f0f3f8}.machine-name-line em{padding:3px 6px;border-radius:999px;color:#b56a06;background:#fff4dc;font-size:8px;font-style:normal;font-weight:800}.machine-row-meta{margin-top:6px;color:#8490a4;font-size:8px}.machine-health-table td:first-child p{margin:6px 0 0;color:#728097;font-size:9px}.machine-row-load{min-width:190px;display:grid;gap:6px}.machine-load-row{display:grid;grid-template-columns:38px 1fr;gap:5px 8px;align-items:center;font-size:8px}.machine-load-row>span{color:#7b879b}.machine-load-row>strong{text-align:right;font-size:8px}.machine-load-row>i{grid-column:1/-1;height:4px;overflow:hidden;border-radius:99px;background:#edf0f5}.machine-load-row>i b{height:100%;display:block;border-radius:inherit;background:#315dff}.machine-node-count{display:grid;gap:2px;padding:0;border:0;color:#34425b;background:transparent;cursor:pointer;text-align:left}.machine-node-count strong{font-size:18px}.machine-node-count span{color:#8a95a7;font-size:8px}.machine-health-table td:nth-child(5)>strong,.machine-health-table td:nth-child(5)>small{display:block}.machine-health-table td:nth-child(5)>small{margin-top:5px;color:#8c97a9}.machine-row-actions{display:flex;gap:5px}.machine-row-actions button{width:28px;height:28px;border:1px solid #dde4ef;border-radius:7px;color:#3154cc;background:#fff;cursor:pointer}.machine-row-actions button.danger{color:#dc4c5b}.machine-modal header>div p,.machine-associate-modal header>div p{margin:4px 0 0;color:#7a869a;font-size:10px}.machine-form .machine-check span{display:grid;gap:3px}.machine-form .machine-check small{color:#7d899d;font-size:10px;font-weight:500}.machine-detail-backdrop{place-items:center}.machine-detail-modal{width:min(1050px,calc(100vw - 42px));max-height:calc(100vh - 34px)}.machine-detail-heading>div{display:flex;align-items:center;gap:8px}.machine-detail-heading>div span,.machine-detail-heading>div em{padding:4px 7px;border-radius:999px;background:#eef2f8;color:#647087;font-size:9px;font-style:normal;font-weight:800}.machine-detail-heading>div em.online{color:#16785d;background:#e6f7ef}.machine-detail-heading>div em.warning{color:#a35b00;background:#fff1d6}.machine-detail-heading p{margin:5px 0 0;color:#6f7c91;font-size:10px}.machine-detail-heading small{display:block;margin-top:4px;color:#8792a5}.machine-detail-body{padding:20px 24px 30px;overflow-y:auto;background:#f7f9fc}.machine-detail-quick,.machine-section-title,.machine-section-title>div{display:flex;align-items:center;gap:8px}.machine-detail-quick{justify-content:flex-end;margin-bottom:12px}.machine-detail-quick button,.machine-section-title button,.machine-token-actions button{padding:8px 10px;border:1px solid #dce3ed;border-radius:8px;color:#3551a8;background:#fff;cursor:pointer;font-size:9px;font-weight:750}.machine-detail-quick button.primary{color:#fff;background:#315dff}.machine-detail-section{margin-top:12px;padding:18px;border:1px solid #e2e7ef;border-radius:13px;background:#fff}.machine-detail-section>h3,.machine-section-title h3{margin:0;font-size:14px}.machine-detail-section>p,.machine-section-title p{margin:5px 0 10px;color:#7c879a;font-size:9px}.machine-section-title{justify-content:space-between}.machine-section-title>div:first-child{display:block}.machine-history-controls,.machine-history-controls>span{display:flex;gap:5px;align-items:center}.machine-history-controls>span+span{padding-left:7px;border-left:1px solid #e4e8ee}.machine-history-controls button{padding:5px 7px;border:1px solid #e0e5ee;border-radius:6px;color:#738096;background:#fff;cursor:pointer;font-size:8px}.machine-history-controls button.active{border-color:#bed0ff;color:#264ad3;background:#edf2ff}.machine-chart{height:176px;margin-top:12px}.machine-chart svg{width:100%;height:146px;overflow:visible}.machine-chart line{stroke:#e8ecf2;stroke-width:.6}.machine-chart polyline{fill:none;stroke:#315dff;stroke-width:1.4;vector-effect:non-scaling-stroke}.machine-chart>div{display:flex;justify-content:space-between;color:#8994a7;font-size:8px}.machine-chart>div strong{color:#3154cc}.machine-chart-empty{min-height:100px;display:grid;place-items:center;color:#929cad!important}.machine-current-load{display:grid;grid-template-columns:repeat(3,minmax(0,1fr)) minmax(160px,1fr);gap:12px;margin-top:14px}.machine-current-load>.machine-load-row,.machine-network{padding:12px;border-radius:9px;background:#f6f8fb}.machine-network{display:grid;gap:8px;color:#7b879b;font-size:9px}.machine-network strong{color:#364158}.machine-token-actions{display:flex;gap:7px;margin-bottom:10px}.machine-detail-section>.machine-credential-value{margin-top:9px}.machine-detail-section>small{display:block;margin-top:8px;color:#8994a6}.machine-linked-table{margin:14px 0 0}.machine-node-link,.machine-detach{padding:0;border:0;color:#3154cc;background:transparent;cursor:pointer;font:inherit;font-weight:750}.machine-detach{color:#d34e5c}.machine-switch{display:inline-block;position:relative;width:34px;height:19px}.machine-switch input{position:absolute;opacity:0}.machine-switch span{position:absolute;inset:0;border-radius:99px;background:#d8dee8;cursor:pointer}.machine-switch span:after{content:"";position:absolute;top:3px;left:3px;width:13px;height:13px;border-radius:50%;background:#fff;transition:.15s}.machine-switch input:checked+span{background:#315dff}.machine-switch input:checked+span:after{transform:translateX(15px)}.machine-associate-backdrop{z-index:140}.machine-associate-modal{width:min(620px,calc(100vw - 32px))}.machine-associate-body{display:grid;gap:12px;padding:18px 22px;overflow:auto}.machine-associate-selectall{display:flex;align-items:center;justify-content:space-between;color:#748096;font-size:10px}.machine-associate-selectall button{padding:7px 10px;border:1px solid #dce3ed;border-radius:8px;background:#fff;color:#3154cc;font:inherit;font-weight:750}.machine-associate-selectall button:disabled{opacity:.45}.machine-associate-list{max-height:330px;display:grid;gap:7px;overflow:auto}.machine-associate-list>p{padding:38px;color:#8a95a7;text-align:center}.machine-associate-list label{display:flex;gap:11px;align-items:center;padding:10px;border:1px solid #e3e8f0;border-radius:9px;cursor:pointer}.machine-associate-list label>span{display:grid;gap:3px}.machine-associate-list small{color:#8590a3}.machine-associate-modal footer>span{margin-right:auto;color:#768297;font-size:10px}.admin-modal footer button.primary{color:#fff;background:#315dff}.machine-credential-value code{max-height:105px;overflow:auto}@media(max-width:1000px){.machine-summary-grid{grid-template-columns:repeat(3,1fr)}.machine-current-load{grid-template-columns:repeat(2,1fr)}.machine-overview-note{align-items:flex-start;flex-direction:column}.machine-history-controls{align-items:flex-end;flex-direction:column}}@media(max-width:720px){.machine-summary-grid{grid-template-columns:repeat(2,1fr)}.machine-filter-bar{grid-template-columns:1fr}.machine-current-load{grid-template-columns:1fr}.machine-section-title{align-items:flex-start;flex-direction:column}.machine-detail-modal{width:calc(100vw - 16px)}.machine-detail-body{padding:12px}.machine-history-controls{width:100%;align-items:flex-start}.machine-history-controls>span{flex-wrap:wrap}.machine-history-controls>span+span{padding:0;border:0}}
`;
