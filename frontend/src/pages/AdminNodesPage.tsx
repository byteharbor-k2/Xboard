import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from "react";

import { listNodeGroups, saveNodeGroup } from "../admin/groupManagementApi";
import { listMachines, type ManagedMachine } from "../admin/machineManagementApi";
import {
  NODE_PROTOCOLS,
  batchDeleteNodes,
  batchResetNodeTraffic,
  batchUpdateNodes,
  copyNode,
  deleteNode,
  generateEchKey,
  listNodes,
  resetNodeTraffic,
  saveNode,
  sortNodes,
  updateNode,
  type ManagedNode,
  type NodeDraft,
  type NodeProtocol,
  type RateTimeRange
} from "../admin/nodeManagementApi";
import { listNodeRoutes } from "../admin/routeManagementApi";
import { AdminShell } from "../components/AdminShell";
import { ApiError } from "../lib/http";
import { useAdminAuthStore } from "../store/adminAuth";
import { ConfirmBar, type ConfirmRequest } from "../components/ConfirmBar";
import { useAdminPreferences } from "../store/adminPreferences";
import "./AdminNodesPage.css";

const protocolNames: Record<NodeProtocol, string> = {
  shadowsocks: "Shadowsocks", vmess: "VMess", vless: "VLESS", trojan: "Trojan",
  hysteria: "Hysteria", tuic: "TUIC", anytls: "AnyTLS", socks: "SOCKS",
  naive: "Naive", http: "HTTP", mieru: "Mieru"
};

const cipherOptions = [
  "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
  "aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305"
];
const transportOptions = ["tcp", "tcp-http", "grpc", "ws", "h2", "httpupgrade", "xhttp"];
const fingerprintOptions = ["chrome", "firefox", "safari", "edge", "ios", "android", "random"];
const validGroupName = /^[\p{Script=Han}A-Za-z0-9_-]{2,50}$/u;

function defaults(type: NodeProtocol): Record<string, unknown> {
  switch (type) {
    case "shadowsocks": return { cipher: "2022-blake3-aes-128-gcm", plugin: "", plugin_opts: "" };
    case "vmess": return { tls: 0, network: "tcp", network_settings: {}, tls_settings: {}, utls: { enabled: false, fingerprint: "chrome" }, multiplex: { enabled: false, protocol: "smux" } };
    case "vless": return { tls: 0, network: "tcp", network_settings: {}, flow: "", encryption: { enabled: false, encryption: "", decryption: "" }, tls_settings: {}, reality_settings: {}, utls: { enabled: false, fingerprint: "chrome" }, multiplex: { enabled: false, protocol: "smux" } };
    case "trojan": return { tls: 1, network: "tcp", network_settings: {}, server_name: "", allow_insecure: false, tls_settings: {}, reality_settings: {}, utls: { enabled: false, fingerprint: "chrome" }, multiplex: { enabled: false, protocol: "smux" } };
    case "hysteria": return { version: 2, alpn: "h3", obfs: { open: false, type: "salamander", password: "" }, bandwidth: { up: 100, down: 100 }, hop_interval: 30, tls: { server_name: "", allow_insecure: false } };
    case "tuic": return { version: 5, password: "", congestion_control: "bbr", alpn: ["h3"], udp_relay_mode: "native", tls: { server_name: "", allow_insecure: false } };
    case "anytls": return { tls: { server_name: "", allow_insecure: false }, padding_scheme: [] };
    case "naive": return { tls: 1, tls_settings: { server_name: "", allow_insecure: false } };
    case "http": return { tls: 0, tls_settings: { server_name: "", allow_insecure: false } };
    case "socks": return { version: 5, tls: 0, tls_settings: { server_name: "", allow_insecure: false } };
    case "mieru": return { transport: "TCP", traffic_pattern: "", multiplex: { enabled: false, protocol: "smux" } };
  }
}

function makeDraft(type: NodeProtocol = "shadowsocks"): NodeDraft {
  return {
    type, code: null, parent_id: null, name: "", machine_id: null, host: "", port: 443,
    server_port: 443, rate: 1, rate_time_enable: false, rate_time_ranges: [],
    transfer_enable: 0, show: true, enabled: true, protocol_settings: { listen_ip: "0.0.0.0", ...defaults(type) },
    group_ids: [], route_ids: [], tags: [], custom_outbounds: [], custom_routes: [],
    cert_config: null
  };
}

type NodeColumn = "id" | "visibility" | "node" | "deployment" | "address" | "runtime" | "online" | "rate" | "groups" | "traffic";

function formatNodeTime(value: number | null | undefined, language: "zh-CN" | "en-US") {
  if (!value) return language === "zh-CN" ? "暂无" : "Never";
  return new Date(value * 1000).toLocaleString(language);
}

function numericValue(source: Record<string, unknown>, keys: string[]) {
  for (const key of keys) {
    const value = readPath(source, key, Number.NaN);
    const number = Number(value);
    if (Number.isFinite(number)) return number;
  }
  return null;
}

/**
 * A node bound to a machine that is itself reporting, yet has gone silent, is
 * almost always a node whose kernel refused to start - a port collision, a bad
 * certificate. The agent has no channel to report that back, so the panel would
 * otherwise render it identically to a node that was only just created.
 */
function nodeStalled(
  node: ManagedNode,
  machines: ManagedMachine[] | undefined
): boolean {
  if (!node.enabled || node.machine_id === null) return false;
  const machine = machines?.find((entry) => entry.id === node.machine_id);
  const now = Math.floor(Date.now() / 1000);
  if (!machine?.last_seen_at || now - machine.last_seen_at > 180) return false;
  const lastPush = node.last_push_at ?? node.last_check_at;
  return !lastPush || now - lastPush > 180;
}

function runtimeSummary(node: ManagedNode) {
  const source = { ...asObject(node.metrics), ...asObject(node.load_status) };
  return {
    cpu: numericValue(source, ["cpu", "cpu_usage", "cpu_percent"]),
    memory: numericValue(source, ["memory", "memory_usage", "mem.percent", "mem.usage"]),
    connections: numericValue(source, ["connections", "online_conn", "connection_count"]),
    raw: source
  };
}

function formatBytes(value: number) {
  if (!value) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB", "PB"];
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1);
  return `${(value / 1024 ** index).toFixed(index > 2 ? 2 : 1)} ${units[index]}`;
}

function asObject(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function readPath(source: Record<string, unknown>, path: string, fallback: unknown = "") {
  let current: unknown = source;
  for (const part of path.split(".")) current = asObject(current)[part];
  return current ?? fallback;
}

function writePath(source: Record<string, unknown>, path: string, value: unknown) {
  const result = structuredClone(source);
  const parts = path.split(".");
  let cursor = result;
  parts.slice(0, -1).forEach((part) => {
    cursor[part] = asObject(cursor[part]);
    cursor = cursor[part] as Record<string, unknown>;
  });
  cursor[parts.at(-1)!] = value;
  return result;
}

function parseObject(value: string, message: string) {
  const parsed = JSON.parse(value) as unknown;
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error(message);
  return parsed as Record<string, unknown>;
}

function parseArray(value: string, message: string) {
  const parsed = JSON.parse(value) as unknown;
  if (!Array.isArray(parsed)) throw new Error(message);
  return parsed;
}

function InputField({ label, children, wide = false, hint }: { label: string; children: ReactNode; wide?: boolean; hint?: string }) {
  return <label className={wide ? "node-field node-field-wide" : "node-field"}><span>{label}</span>{children}{hint && <small>{hint}</small>}</label>;
}

function Toggle({ checked, onChange, label, hint }: { checked: boolean; onChange: (value: boolean) => void; label: string; hint?: string }) {
  return <label className="node-toggle"><input checked={checked} onChange={(event) => onChange(event.target.checked)} type="checkbox" /><span aria-hidden="true" />
    <div><strong>{label}</strong>{hint && <small>{hint}</small>}</div></label>;
}

export function AdminNodesPage() {
  const language = useAdminPreferences((state) => state.language);
  const token = useAdminAuthStore((state) => state.accessToken)!;
  const zh = language === "zh-CN";
  const tx = (cn: string, en: string) => zh ? cn : en;
  const client = useQueryClient();
  const requestedMachineId = useMemo(() => {
    const raw = new URLSearchParams(window.location.search).get("machine_id");
    if (!raw) return null;
    const value = Number(raw);
    return Number.isInteger(value) && value > 0 ? value : null;
  }, []);
  const requestedCreate = useMemo(
    () => new URLSearchParams(window.location.search).get("open_create") === "1",
    []
  );
  const queryRequestHandled = useRef(false);
  const [editing, setEditing] = useState<ManagedNode | null | undefined>();
  const [draft, setDraft] = useState<NodeDraft>(() => makeDraft());
  const [protocolJson, setProtocolJson] = useState("{}");
  const [outboundsJson, setOutboundsJson] = useState("[]");
  const [routesJson, setRoutesJson] = useState("[]");
  const [certJson, setCertJson] = useState("{}");
  const [error, setError] = useState("");
  const [search, setSearch] = useState("");
  const [typeFilter, setTypeFilter] = useState("");
  const [machineFilter, setMachineFilter] = useState(requestedMachineId ? String(requestedMachineId) : "");
  const [groupFilter, setGroupFilter] = useState("");
  const [pageSize, setPageSize] = useState(20);
  const [page, setPage] = useState(1);
  const [columns, setColumns] = useState<Record<NodeColumn, boolean>>({
    id: true, visibility: true, node: true, deployment: true, address: true,
    runtime: true, online: true, rate: true, groups: true, traffic: true
  });
  const [selected, setSelected] = useState<number[]>([]);
  const [batchAction, setBatchAction] = useState("");
  const [sorting, setSorting] = useState(false);
  const [order, setOrder] = useState<number[]>([]);
  const [dragging, setDragging] = useState<number | null>(null);
  const [pendingConfirmation, setPendingConfirmation] = useState<ConfirmRequest | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [runtimeNode, setRuntimeNode] = useState<ManagedNode>();
  const [portsSynced, setPortsSynced] = useState(true);
  const [groupComposerOpen, setGroupComposerOpen] = useState(false);
  const [groupName, setGroupName] = useState("");
  const [groupSaving, setGroupSaving] = useState(false);
  const [copiedAddressId, setCopiedAddressId] = useState<number>();

  const nodesQuery = useQuery({ queryKey: ["admin", "nodes"], queryFn: () => listNodes(token) });
  const machinesQuery = useQuery({ queryKey: ["admin", "machines"], queryFn: () => listMachines(token) });
  const groupsQuery = useQuery({ queryKey: ["admin", "node-groups"], queryFn: () => listNodeGroups(token) });
  const routesQuery = useQuery({ queryKey: ["admin", "node-routes"], queryFn: () => listNodeRoutes(token) });
  const refresh = () => client.invalidateQueries({ queryKey: ["admin", "nodes"] });
  const saveMutation = useMutation({ mutationFn: (value: NodeDraft) => saveNode(token, value), onSuccess: refresh });

  useEffect(() => {
    if (nodesQuery.data) setOrder(nodesQuery.data.map((node) => node.id));
  }, [nodesQuery.data]);

  useEffect(() => {
    if (queryRequestHandled.current || !requestedCreate) return;
    queryRequestHandled.current = true;
    const next = { ...makeDraft(), machine_id: requestedMachineId };
    setDraft(next);
    resetEditors(next);
    setPortsSynced(true);
    setEditing(null);
    setError("");
  }, [requestedCreate, requestedMachineId]);

  const nodes = useMemo(() => {
    const source = [...(nodesQuery.data ?? [])];
    const sorted = sorting ? order.map((id) => source.find((node) => node.id === id)).filter(Boolean) as ManagedNode[] : source;
    const term = search.trim().toLowerCase();
    return sorted.filter((node) =>
      (!term || node.name.toLowerCase().includes(term) || String(node.id).includes(term) || (node.host ?? "").toLowerCase().includes(term)) &&
      (!typeFilter || node.type === typeFilter) &&
      (!machineFilter || String(node.machine_id ?? "") === machineFilter) &&
      (!groupFilter || node.group_ids.includes(Number(groupFilter)))
    );
  }, [nodesQuery.data, order, sorting, search, typeFilter, machineFilter, groupFilter]);
  const pageCount = Math.max(1, Math.ceil(nodes.length / pageSize));
  const currentPage = Math.min(page, pageCount);
  const visibleNodes = sorting ? nodes : nodes.slice((currentPage - 1) * pageSize, currentPage * pageSize);

  useEffect(() => setPage(1), [search, typeFilter, machineFilter, groupFilter, pageSize]);

  function resetEditors(next: NodeDraft) {
    setProtocolJson(JSON.stringify(next.protocol_settings, null, 2));
    setOutboundsJson(JSON.stringify(next.custom_outbounds, null, 2));
    setRoutesJson(JSON.stringify(next.custom_routes, null, 2));
    setCertJson(JSON.stringify(next.cert_config ?? {}, null, 2));
  }

  function openCreate() {
    const next = makeDraft();
    setDraft(next); resetEditors(next); setPortsSynced(true); setGroupComposerOpen(false); setGroupName(""); setEditing(null); setError("");
  }

  function openEdit(node: ManagedNode) {
    const next: NodeDraft = {
      id: node.id, type: node.type, code: node.code, parent_id: node.parent_id,
      machine_id: node.machine_id, group_ids: [...node.group_ids], route_ids: [...node.route_ids],
      name: node.name, rate: Number(node.rate), rate_time_enable: node.rate_time_enable,
      rate_time_ranges: Array.isArray(node.rate_time_ranges) ? node.rate_time_ranges as RateTimeRange[] : [],
      transfer_enable: node.transfer_enable, tags: [...node.tags], host: node.host ?? "",
      port: node.port, server_port: node.server_port, protocol_settings: structuredClone(node.protocol_settings),
      custom_outbounds: Array.isArray(node.custom_outbounds) ? structuredClone(node.custom_outbounds) : [],
      custom_routes: Array.isArray(node.custom_routes) ? structuredClone(node.custom_routes) : [],
      cert_config: node.cert_config ? structuredClone(node.cert_config) : null,
      show: node.show, enabled: node.enabled, sort: node.sort
    };
    setDraft(next); resetEditors(next); setPortsSynced(node.port === node.server_port); setGroupComposerOpen(false); setGroupName(""); setEditing(node); setError("");
  }

  function changeType(type: NodeProtocol) {
    const settings = { listen_ip: String(readPath(draft.protocol_settings, "listen_ip", "0.0.0.0")), ...defaults(type) };
    setDraft((current) => ({ ...current, type, protocol_settings: settings, parent_id: null }));
    setProtocolJson(JSON.stringify(settings, null, 2));
  }

  function changeProtocol(path: string, value: unknown) {
    let base = draft.protocol_settings;
    try { base = parseObject(protocolJson, "invalid"); } catch { /* keep last valid structured state */ }
    const next = writePath(base, path, value);
    setDraft((current) => ({ ...current, protocol_settings: next }));
    setProtocolJson(JSON.stringify(next, null, 2));
  }

  async function submit(event: FormEvent) {
    event.preventDefault(); setError("");
    try {
      const protocol = parseObject(protocolJson, tx("协议设置必须是 JSON 对象", "Protocol settings must be a JSON object"));
      const outbounds = parseArray(outboundsJson, tx("自定义出站必须是 JSON 数组", "Custom outbounds must be a JSON array"));
      const customRoutes = parseArray(routesJson, tx("自定义路由必须是 JSON 数组", "Custom routes must be a JSON array"));
      const cert = parseObject(certJson, tx("证书设置必须是 JSON 对象", "Certificate settings must be a JSON object"));
      await saveMutation.mutateAsync({ ...draft, protocol_settings: protocol, custom_outbounds: outbounds, custom_routes: customRoutes, cert_config: Object.keys(cert).length ? cert : null });
      setEditing(undefined);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : caught instanceof Error ? caught.message : tx("请求未能完成", "Request failed"));
    }
  }

  async function action(work: () => Promise<unknown>) {
    setError("");
    try { await work(); await refresh(); }
    catch (caught) { setError(caught instanceof ApiError ? caught.message : tx("请求未能完成", "Request failed")); }
  }

  async function runBatch() {
    if (!selected.length || !batchAction) return;
    const actionType = batchAction;
    const ids = [...selected];
    const execute = async () => {
      await action(async () => {
        if (actionType === "show") await batchUpdateNodes(token, { ids, show: true });
        if (actionType === "hide") await batchUpdateNodes(token, { ids, show: false });
        if (actionType === "enable") await batchUpdateNodes(token, { ids, enabled: true });
        if (actionType === "disable") await batchUpdateNodes(token, { ids, enabled: false });
        if (actionType === "reset") await batchResetNodeTraffic(token, ids);
        if (actionType === "delete") await batchDeleteNodes(token, ids);
      });
      setSelected([]);
      setBatchAction("");
    };
    if (actionType === "delete" || actionType === "reset") {
      setPendingConfirmation({
        message: actionType === "delete"
          ? tx(`将永久删除选中的 ${ids.length} 个节点，此操作无法撤销。`, `This permanently deletes ${ids.length} selected nodes and cannot be undone.`)
          : tx(`将清零选中的 ${ids.length} 个节点的流量统计。`, `This resets traffic counters for ${ids.length} selected nodes.`),
        confirmLabel: actionType === "delete" ? tx("确认删除", "Delete nodes") : tx("确认重置", "Reset traffic"),
        danger: actionType === "delete",
        run: execute
      });
      return;
    }
    await execute();
  }

  function requestNodeAction(node: ManagedNode, type: "reset" | "delete") {
    setPendingConfirmation({
      message: type === "delete"
        ? tx(`将永久删除节点「${node.name}」，此操作无法撤销。`, `This permanently deletes “${node.name}” and cannot be undone.`)
        : tx(`将清零节点「${node.name}」的流量统计。`, `This resets traffic counters for “${node.name}”.`),
      confirmLabel: type === "delete" ? tx("确认删除", "Delete node") : tx("确认重置", "Reset traffic"),
      danger: type === "delete",
      run: () => action(() => type === "delete" ? deleteNode(token, node.id) : resetNodeTraffic(token, node.id))
    });
  }

  async function confirmPendingAction() {
    if (!pendingConfirmation || confirming) return;
    setConfirming(true);
    try {
      await pendingConfirmation.run();
      setPendingConfirmation(null);
    } finally {
      setConfirming(false);
    }
  }

  async function saveOrder() {
    await action(() => sortNodes(token, order.map((id, index) => ({ id, order: index }))));
    setSorting(false);
  }

  async function quickAddGroup() {
    const name = groupName.trim();
    if (!name) return;
    if (!validGroupName.test(name)) {
      setError(tx("权限组名称必须为 2–50 个字符，且只能包含中文、英文、数字、下划线或连字符。", "Use 2–50 Chinese or English letters, digits, underscores, or hyphens."));
      return;
    }
    setError(""); setGroupSaving(true);
    try {
      await saveNodeGroup(token, { name });
      const groups = await client.fetchQuery({ queryKey: ["admin", "node-groups"], queryFn: () => listNodeGroups(token) });
      const created = groups.find((group) => group.name === name);
      if (created) setDraft((current) => ({ ...current, group_ids: Array.from(new Set([...current.group_ids, created.id])) }));
      setGroupName(""); setGroupComposerOpen(false);
    } catch (caught) { setError(caught instanceof ApiError ? caught.message : tx("权限组创建失败", "Could not create access group")); }
    finally { setGroupSaving(false); }
  }

  function resetFilters() {
    setSearch(""); setTypeFilter(""); setMachineFilter(""); setGroupFilter(""); setPage(1);
  }

  async function copyAddress(node: ManagedNode) {
    const value = `${node.host ?? ""}:${node.port ?? node.server_port}`;
    try { await navigator.clipboard.writeText(value); }
    catch {
      const input = document.createElement("textarea");
      input.value = value; input.style.position = "fixed"; input.style.opacity = "0";
      document.body.appendChild(input); input.select(); document.execCommand("copy"); input.remove();
    }
    setCopiedAddressId(node.id);
    window.setTimeout(() => setCopiedAddressId((current) => current === node.id ? undefined : current), 1_500);
  }

  function dropOn(target: number) {
    if (dragging == null || dragging === target) return;
    setOrder((current) => {
      const result = current.filter((id) => id !== dragging);
      result.splice(result.indexOf(target), 0, dragging);
      return result;
    });
  }

  function toggleRoute(routeId: number, checked: boolean) {
    setDraft((current) => ({
      ...current,
      route_ids: checked
        ? [...current.route_ids, routeId]
        : current.route_ids.filter((id) => id !== routeId)
    }));
  }

  function moveRoute(routeId: number, offset: -1 | 1) {
    setDraft((current) => {
      const index = current.route_ids.indexOf(routeId);
      const destination = index + offset;
      if (index < 0 || destination < 0 || destination >= current.route_ids.length) return current;
      const routeIds = [...current.route_ids];
      [routeIds[index], routeIds[destination]] = [routeIds[destination], routeIds[index]];
      return { ...current, route_ids: routeIds };
    });
  }

  const allVisibleSelected = visibleNodes.length > 0 && visibleNodes.every((node) => selected.includes(node.id));

  return <AdminShell>
    <header className="admin-page-heading admin-page-heading-action"><div><p>{tx("节点管理", "Infrastructure")}</p><h1>{tx("节点管理", "Nodes")}</h1>
      <span>{tx("配置节点协议、服务器部署、权限组、路由和高级传输参数。", "Manage protocols, deployment machines, access groups, routes, and advanced transport settings.")}</span></div>
      <button className="admin-primary-button" onClick={openCreate} type="button">+ {tx("添加节点", "Add node")}</button></header>

    <section className="node-toolbar">
      <input aria-label={tx("搜索节点", "Search nodes")} onChange={(event) => setSearch(event.target.value)} placeholder={tx("搜索节点名称、ID 或地址...", "Search name, ID, or address...")} value={search} />
      <select aria-label={tx("协议筛选", "Protocol filter")} onChange={(event) => setTypeFilter(event.target.value)} value={typeFilter}><option value="">{tx("全部类型", "All types")}</option>{NODE_PROTOCOLS.map((type) => <option key={type} value={type}>{protocolNames[type]}</option>)}</select>
      <select aria-label={tx("服务器筛选", "Machine filter")} onChange={(event) => setMachineFilter(event.target.value)} value={machineFilter}><option value="">{tx("全部服务器", "All machines")}</option>{machinesQuery.data?.map((machine) => <option key={machine.id} value={machine.id}>{machine.name}</option>)}</select>
      <select aria-label={tx("权限组筛选", "Group filter")} onChange={(event) => setGroupFilter(event.target.value)} value={groupFilter}><option value="">{tx("全部权限组", "All groups")}</option>{groupsQuery.data?.map((group) => <option key={group.id} value={group.id}>{group.name}</option>)}</select>
      <button disabled={!search && !typeFilter && !machineFilter && !groupFilter} onClick={resetFilters} type="button">{tx("重置", "Reset")}</button>
      <details className="node-column-menu"><summary>{tx("列显示", "Columns")}</summary><div>{([
        ["id", tx("节点 ID", "Node ID")], ["visibility", tx("显隐", "Visibility")], ["node", tx("节点", "Node")],
        ["deployment", tx("部署方式", "Deployment")], ["address", tx("地址", "Address")], ["runtime", tx("运行指标", "Runtime")],
        ["online", tx("在线人数", "Online")], ["rate", tx("倍率", "Rate")], ["groups", tx("权限组", "Groups")], ["traffic", tx("流量使用", "Traffic")]
      ] as [NodeColumn, string][]).map(([key, label]) => <label key={key}><input checked={columns[key]} onChange={(event) => setColumns({ ...columns, [key]: event.target.checked })} type="checkbox" />{label}</label>)}</div></details>
      <div className="node-toolbar-spacer" />
      {sorting ? <><button onClick={() => { setSorting(false); setOrder(nodesQuery.data?.map((node) => node.id) ?? []); }} type="button">{tx("取消排序", "Cancel")}</button><button className="primary" onClick={() => void saveOrder()} type="button">{tx("保存排序", "Save order")}</button></> : <button onClick={() => setSorting(true)} type="button">↕ {tx("编辑排序", "Sort")}</button>}
    </section>

    {selected.length > 0 && <section className="node-batchbar"><strong>{tx(`已选择 ${selected.length} 项`, `${selected.length} selected`)}</strong><select onChange={(event) => setBatchAction(event.target.value)} value={batchAction}>
      <option value="">{tx("批量操作", "Batch action")}</option><option value="show">{tx("显示", "Show")}</option><option value="hide">{tx("隐藏", "Hide")}</option>
      <option value="enable">{tx("启用", "Enable")}</option><option value="disable">{tx("停用", "Disable")}</option><option value="reset">{tx("重置流量", "Reset traffic")}</option><option value="delete">{tx("删除", "Delete")}</option>
    </select><button disabled={!batchAction} onClick={() => void runBatch()} type="button">{tx("执行", "Apply")}</button></section>}
    {pendingConfirmation && <ConfirmBar busy={confirming} language={language} onCancel={() => setPendingConfirmation(null)}
      onConfirm={() => void confirmPendingAction()} request={pendingConfirmation} />}
    {error && <p className="admin-operation-error">{error}</p>}

    <section className="admin-card admin-table-wrap node-table-card">
      {nodesQuery.isPending ? <p className="admin-table-empty">{tx("正在加载节点…", "Loading nodes...")}</p> : !nodes.length ? <p className="admin-table-empty">{tx("没有符合条件的节点。", "No matching nodes.")}</p> :
        <table className="admin-table node-table"><thead><tr><th><input aria-label={tx("全选", "Select all")} checked={allVisibleSelected} onChange={(event) => setSelected(event.target.checked ? Array.from(new Set([...selected, ...visibleNodes.map((node) => node.id)])) : selected.filter((id) => !visibleNodes.some((node) => node.id === id)))} type="checkbox" /></th>
          {sorting && <th>{tx("排序", "Order")}</th>}{columns.id && <th>{tx("节点 ID", "Node ID")}</th>}{columns.visibility && <th>{tx("显隐", "Visibility")}</th>}{columns.node && <th>{tx("节点", "Node")}</th>}{columns.deployment && <th>{tx("部署方式", "Deployment")}</th>}{columns.address && <th>{tx("地址", "Address")}</th>}{columns.runtime && <th>{tx("运行指标", "Runtime")}</th>}{columns.online && <th>{tx("在线人数", "Online")}</th>}{columns.rate && <th>{tx("倍率", "Rate")}</th>}{columns.groups && <th>{tx("权限组", "Groups")}</th>}{columns.traffic && <th>{tx("流量使用", "Traffic")}</th>}<th>{tx("操作", "Actions")}</th></tr></thead>
          <tbody>{visibleNodes.map((node) => { const runtime = runtimeSummary(node); const stalled = nodeStalled(node, machinesQuery.data); return <tr draggable={sorting} key={node.id} onDragOver={(event) => sorting && event.preventDefault()} onDragStart={() => setDragging(node.id)} onDrop={() => dropOn(node.id)}>
            <td><input aria-label={`${tx("选择", "Select")} ${node.name}`} checked={selected.includes(node.id)} onChange={(event) => setSelected(event.target.checked ? [...selected, node.id] : selected.filter((id) => id !== node.id))} type="checkbox" /></td>
            {sorting && <td><span className="node-drag-handle" title={tx("拖动排序", "Drag to sort")}>⠿</span></td>}
            {columns.id && <td><code>#{node.id}</code></td>}{columns.visibility && <td><button className={node.show ? "node-icon-toggle on" : "node-icon-toggle"} onClick={() => void action(() => updateNode(token, node.id, { show: !node.show }))} title={node.show ? tx("点击隐藏", "Hide") : tx("点击显示", "Show")} type="button">{node.show ? "◉" : "○"}</button></td>}
            {columns.node && <td><strong>{node.name}</strong><small>{protocolNames[node.type]}{node.parent_id ? ` · ${tx("子节点", "Child")} #${node.parent_id}` : ""}</small></td>}
            {columns.deployment && <td><select aria-label={`${tx("部署服务器", "Deployment machine")} ${node.name}`} className="node-inline-select" onChange={(event) => void action(() => updateNode(token, node.id, { machine_id: event.target.value ? Number(event.target.value) : null }))} value={node.machine_id ?? ""}><option value="">{tx("未绑定", "Unbound")}</option>{machinesQuery.data?.map((machine) => <option key={machine.id} value={machine.id}>{machine.name}</option>)}</select><label className="node-switch"><input aria-label={`${tx("启用节点", "Enable node")} ${node.name}`} checked={node.enabled} onChange={() => void action(() => updateNode(token, node.id, { enabled: !node.enabled }))} type="checkbox" /><span /><small>{node.enabled ? tx("运行中", "Enabled") : tx("已停用", "Disabled")}</small></label></td>}
            {columns.address && <td><div className="node-address-cell"><span>{node.host || "—"}<small>{node.port ?? node.server_port} / {tx("内", "internal")} {node.server_port}</small></span><button aria-label={tx("复制地址", "Copy address")} onClick={() => void copyAddress(node)} title={copiedAddressId === node.id ? tx("已复制", "Copied") : tx("复制地址", "Copy address")} type="button">{copiedAddressId === node.id ? "✓" : "⧉"}</button></div></td>}
            {columns.runtime && <td><button className={stalled ? "node-runtime-button stalled" : "node-runtime-button"} onClick={() => setRuntimeNode(node)} title={stalled ? tx("服务器在线但该节点未上报，通常是节点内核启动失败（端口占用、证书错误等）。", "The machine is reporting but this node is not, which usually means its kernel failed to start (port already in use, certificate error).") : undefined} type="button"><strong>{stalled ? tx("⚠ 未上报", "⚠ Not reporting") : runtime.cpu === null ? tx("暂无上报", "No report") : `CPU ${runtime.cpu.toFixed(1)}%`}</strong><small>{stalled ? tx("服务器在线，节点可能启动失败", "Machine online, node may have failed to start") : `${tx("最后推送", "Last push")}: ${formatNodeTime(node.last_push_at ?? node.last_check_at, language)}`}</small></button></td>}
            {columns.online && <td>{node.onlineUsers}<small>{node.online_conn} {tx("连接", "connections")}</small></td>}{columns.rate && <td>{Number(node.rate).toFixed(2)}x</td>}
            {columns.groups && <td><div className="node-chip-list">{node.group_ids.length ? node.group_ids.map((id) => <span key={id}>{groupsQuery.data?.find((group) => group.id === id)?.name ?? `#${id}`}</span>) : <em>—</em>}</div></td>}
            {columns.traffic && <td>↑ {formatBytes(node.u)}<small>↓ {formatBytes(node.d)}</small></td>}
            <td><div className="node-row-actions"><button onClick={() => openEdit(node)} type="button">{tx("编辑", "Edit")}</button><button onClick={() => void action(() => copyNode(token, node.id))} type="button">{tx("复制", "Copy")}</button>
              <button onClick={() => requestNodeAction(node, "reset")} type="button">{tx("重置", "Reset")}</button>
              <button className="danger" onClick={() => requestNodeAction(node, "delete")} type="button">{tx("删除", "Delete")}</button></div></td>
          </tr>; })}</tbody></table>}
      {!sorting && nodes.length > 0 && <div className="admin-pagination node-pagination"><span>{tx(`共 ${nodes.length} 项`, `${nodes.length} items`)}</span><label>{tx("每页显示", "Per page")}<select value={pageSize} onChange={(event) => setPageSize(Number(event.target.value))}>{[10, 20, 50, 100].map((size) => <option key={size} value={size}>{size}</option>)}</select></label><span>{tx(`第 ${currentPage} / ${pageCount} 页`, `Page ${currentPage} of ${pageCount}`)}</span><div><button aria-label={tx("首页", "First")} disabled={currentPage === 1} onClick={() => setPage(1)} type="button">«</button><button aria-label={tx("上一页", "Previous")} disabled={currentPage === 1} onClick={() => setPage(currentPage - 1)} type="button">‹</button><button aria-label={tx("下一页", "Next")} disabled={currentPage === pageCount} onClick={() => setPage(currentPage + 1)} type="button">›</button><button aria-label={tx("末页", "Last")} disabled={currentPage === pageCount} onClick={() => setPage(pageCount)} type="button">»</button></div></div>}
    </section>

    {runtimeNode && <div className="admin-modal-backdrop" role="presentation"><section className="admin-modal node-runtime-modal"><header><div><small>{tx("运行指标", "Runtime metrics")}</small><h2>#{runtimeNode.id} {runtimeNode.name}</h2></div><button aria-label={tx("关闭", "Close")} onClick={() => setRuntimeNode(undefined)} type="button">×</button></header><div className="node-runtime-body">{(() => { const runtime = runtimeSummary(runtimeNode); return <><div className="node-runtime-grid"><article><span>CPU</span><strong>{runtime.cpu === null ? "—" : `${runtime.cpu.toFixed(1)}%`}</strong></article><article><span>{tx("内存", "Memory")}</span><strong>{runtime.memory === null ? "—" : `${runtime.memory.toFixed(1)}%`}</strong></article><article><span>{tx("连接数", "Connections")}</span><strong>{runtime.connections ?? runtimeNode.online_conn}</strong></article><article><span>{tx("最后推送", "Last push")}</span><strong>{formatNodeTime(runtimeNode.last_push_at ?? runtimeNode.last_check_at, language)}</strong></article></div><details className="node-advanced-json" open><summary>{tx("完整上报数据", "Complete report")}</summary><pre>{JSON.stringify({ load_status: runtimeNode.load_status ?? null, metrics: runtimeNode.metrics ?? null }, null, 2)}</pre></details></>; })()}</div><footer><button className="primary" onClick={() => setRuntimeNode(undefined)} type="button">{tx("关闭", "Close")}</button></footer></section></div>}

    {editing !== undefined && <div className="admin-modal-backdrop" role="presentation"><form className="admin-modal node-editor-modal" onSubmit={(event) => void submit(event)}>
      <header><div><small>{tx("节点管理", "Node management")}</small><h2>{editing ? tx("编辑节点", "Edit node") : tx("添加节点", "Add node")}</h2></div><button aria-label={tx("关闭", "Close")} onClick={() => setEditing(undefined)} type="button">×</button></header>
      <div className="node-editor-scroll">
        <section className="node-form-section"><h3>{tx("基础设置", "Base settings")}</h3><div className="node-form-grid">
          <InputField label={tx("节点名称", "Node name")}><input required value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.target.value })} /></InputField>
          <InputField label={tx("协议", "Protocol")}><select value={draft.type} onChange={(event) => changeType(event.target.value as NodeProtocol)}>{NODE_PROTOCOLS.map((type) => <option key={type} value={type}>{protocolNames[type]}</option>)}</select></InputField>
          <InputField label={tx("倍率", "Rate")}><input min="0" required step="0.01" type="number" value={draft.rate} onChange={(event) => setDraft({ ...draft, rate: Number(event.target.value) })} /></InputField>
          <InputField label={tx("流量上限（GB，0 为不限）", "Traffic cap (GB, 0 unlimited)")}><input min="0" type="number" value={draft.transfer_enable ? draft.transfer_enable / 1024 ** 3 : 0} onChange={(event) => setDraft({ ...draft, transfer_enable: Math.round(Number(event.target.value) * 1024 ** 3) })} /></InputField>
          <InputField label={tx("自定义节点 ID / Code", "Custom node ID / code")} hint={tx("留空由系统生成", "Leave blank for automatic generation")}><input value={draft.code ?? ""} onChange={(event) => setDraft({ ...draft, code: event.target.value || null })} /></InputField>
          <InputField label={tx("标签（逗号分隔）", "Tags (comma separated)")}><input value={draft.tags.join(", ")} onChange={(event) => setDraft({ ...draft, tags: event.target.value.split(",").map((item) => item.trim()).filter(Boolean) })} /></InputField>
        </div><div className="node-toggle-row"><Toggle checked={draft.show} label={tx("在用户端显示", "Show to users")} onChange={(show) => setDraft({ ...draft, show })} /><Toggle checked={draft.enabled} label={tx("允许节点运行", "Enable node")} onChange={(enabled) => setDraft({ ...draft, enabled })} /></div></section>

        <section className="node-form-section"><h3>{tx("部署与访问控制", "Deployment and access")}</h3><div className="node-form-grid">
          <InputField label={tx("服务器", "Machine")}><select value={draft.machine_id ?? ""} onChange={(event) => setDraft({ ...draft, machine_id: event.target.value ? Number(event.target.value) : null })}><option value="">{tx("未绑定（手动部署）", "Unbound (manual)")}</option>{machinesQuery.data?.map((machine) => <option key={machine.id} value={machine.id}>{machine.name}</option>)}</select></InputField>
          <InputField label={tx("父节点", "Parent node")}><select value={draft.parent_id ?? ""} onChange={(event) => setDraft({ ...draft, parent_id: event.target.value ? Number(event.target.value) : null })}><option value="">{tx("无父节点", "No parent")}</option>{nodesQuery.data?.filter((node) => node.id !== draft.id && node.type === draft.type && node.parent_id == null).map((node) => <option key={node.id} value={node.id}>#{node.id} {node.name}</option>)}</select></InputField>
          <InputField label={tx("公网域名 / IP", "Public host / IP")}><input required value={draft.host} onChange={(event) => setDraft({ ...draft, host: event.target.value })} /></InputField>
          <InputField label={tx("公网端口", "Public port")}><input max="65535" min="1" required type="number" value={draft.port ?? ""} onChange={(event) => { const port = event.target.value ? Number(event.target.value) : null; setDraft({ ...draft, port, ...(portsSynced && port ? { server_port: port } : {}) }); }} /></InputField>
          <InputField label={tx("监听地址", "Listen address")} hint={tx("xboard-node 默认监听全部网卡", "xboard-node listens on all interfaces by default")}><input value={String(readPath(draft.protocol_settings, "listen_ip", "0.0.0.0"))} onChange={(event) => changeProtocol("listen_ip", event.target.value)} /></InputField>
          <InputField label={tx("服务监听端口", "Server listen port")}><div className="node-input-action"><input max="65535" min="1" required type="number" value={draft.server_port} onChange={(event) => { setPortsSynced(false); setDraft({ ...draft, server_port: Number(event.target.value) }); }} /><button onClick={() => { const port = draft.port ?? draft.server_port; setDraft({ ...draft, server_port: port }); setPortsSynced(true); }} type="button">{portsSynced ? tx("已同步", "Synced") : tx("同步端口", "Sync port")}</button></div></InputField>
        </div>
        <div className="node-access-grid"><fieldset><legend>{tx("权限组", "Access groups")} <button className="node-legend-action" onClick={() => setGroupComposerOpen((open) => !open)} type="button">+ {tx("添加分组", "Add group")}</button></legend>{groupComposerOpen && <div className="node-group-composer"><input autoFocus maxLength={50} placeholder={tx("权限组名称", "Group name")} value={groupName} onChange={(event) => setGroupName(event.target.value)} /><button disabled={groupSaving || !groupName.trim()} onClick={() => void quickAddGroup()} type="button">{groupSaving ? tx("创建中…", "Creating...") : tx("创建", "Create")}</button><button onClick={() => { setGroupComposerOpen(false); setGroupName(""); }} type="button">{tx("取消", "Cancel")}</button></div>}{groupsQuery.data?.map((group) => <label key={group.id}><input checked={draft.group_ids.includes(group.id)} onChange={(event) => setDraft({ ...draft, group_ids: event.target.checked ? [...draft.group_ids, group.id] : draft.group_ids.filter((id) => id !== group.id) })} type="checkbox" />{group.name}<small>{group.users_count} {tx("用户", "users")}</small></label>)}{!groupsQuery.data?.length && <p>{tx("暂无权限组", "No groups")}</p>}</fieldset>
          <fieldset><legend>{tx("路由规则（按顺序）", "Route rules (ordered)")}</legend>
            {draft.route_ids.map((routeId, index) => {
              const route = routesQuery.data?.find((item) => item.id === routeId);
              if (!route) return null;
              return <div className="node-route-order-item" key={route.id}>
                <label><input checked onChange={(event) => toggleRoute(route.id, event.target.checked)} type="checkbox" />{route.remarks}<small>{route.action}</small></label>
                <div><button aria-label={tx(`上移 ${route.remarks}`, `Move ${route.remarks} up`)} disabled={index === 0} onClick={() => moveRoute(route.id, -1)} type="button">↑</button>
                  <button aria-label={tx(`下移 ${route.remarks}`, `Move ${route.remarks} down`)} disabled={index === draft.route_ids.length - 1} onClick={() => moveRoute(route.id, 1)} type="button">↓</button></div>
              </div>;
            })}
            {routesQuery.data?.filter((route) => !draft.route_ids.includes(route.id)).map((route) => <label key={route.id}><input checked={false} onChange={(event) => toggleRoute(route.id, event.target.checked)} type="checkbox" />{route.remarks}<small>{route.action}</small></label>)}
            {!routesQuery.data?.length && <p>{tx("暂无路由规则", "No routes")}</p>}
          </fieldset></div></section>

        <section className="node-form-section"><h3>{protocolNames[draft.type]} {tx("协议设置", "settings")}</h3><ProtocolFields type={draft.type} settings={draft.protocol_settings} json={protocolJson} onChange={changeProtocol} tx={tx} token={token} />
          <details className="node-advanced-json"><summary>{tx("查看 / 编辑完整协议 JSON", "View / edit full protocol JSON")}</summary><textarea spellCheck={false} value={protocolJson} onChange={(event) => setProtocolJson(event.target.value)} /></details></section>

        <section className="node-form-section"><h3>{tx("动态倍率", "Scheduled rate")}</h3><Toggle checked={draft.rate_time_enable} label={tx("按时间段启用不同倍率", "Use time-based rates")} onChange={(rate_time_enable) => setDraft({ ...draft, rate_time_enable })} />
          {draft.rate_time_enable && <div className="node-rate-ranges">{draft.rate_time_ranges.map((range, index) => <div key={`${index}-${range.start}`}><input aria-label={tx("开始时间", "Start time")} type="time" value={range.start} onChange={(event) => setDraft({ ...draft, rate_time_ranges: draft.rate_time_ranges.map((item, itemIndex) => itemIndex === index ? { ...item, start: event.target.value } : item) })} /><span>—</span><input aria-label={tx("结束时间", "End time")} type="time" value={range.end} onChange={(event) => setDraft({ ...draft, rate_time_ranges: draft.rate_time_ranges.map((item, itemIndex) => itemIndex === index ? { ...item, end: event.target.value } : item) })} /><input aria-label={tx("倍率", "Rate")} min="0" step="0.01" type="number" value={range.rate} onChange={(event) => setDraft({ ...draft, rate_time_ranges: draft.rate_time_ranges.map((item, itemIndex) => itemIndex === index ? { ...item, rate: Number(event.target.value) } : item) })} /><span>x</span><button onClick={() => setDraft({ ...draft, rate_time_ranges: draft.rate_time_ranges.filter((_, itemIndex) => itemIndex !== index) })} type="button">×</button></div>)}<button onClick={() => setDraft({ ...draft, rate_time_ranges: [...draft.rate_time_ranges, { start: "00:00", end: "08:00", rate: draft.rate }] })} type="button">+ {tx("添加时间段", "Add range")}</button></div>}
        </section>

        <section className="node-form-section"><h3>{tx("TLS 证书", "TLS certificate")}</h3><CertFields json={certJson} onChange={setCertJson} tx={tx} /></section>

        <section className="node-form-section"><h3>{tx("高级配置", "Advanced configuration")}</h3><div className="node-json-grid">
          <InputField label={tx("自定义出站（JSON 数组）", "Custom outbounds (JSON array)")}><textarea spellCheck={false} value={outboundsJson} onChange={(event) => setOutboundsJson(event.target.value)} /></InputField>
          <InputField label={tx("自定义路由（JSON 数组）", "Custom routes (JSON array)")}><textarea spellCheck={false} value={routesJson} onChange={(event) => setRoutesJson(event.target.value)} /></InputField>
        </div></section>
        {error && <p className="admin-operation-error">{error}</p>}
      </div><footer><button onClick={() => setEditing(undefined)} type="button">{tx("取消", "Cancel")}</button><button className="primary" disabled={saveMutation.isPending} type="submit">{saveMutation.isPending ? tx("保存中…", "Saving...") : tx("保存", "Save")}</button></footer>
    </form></div>}
  </AdminShell>;
}

type ProtocolFieldsProps = {
  type: NodeProtocol;
  settings: Record<string, unknown>;
  json: string;
  onChange: (path: string, value: unknown) => void;
  tx: (zh: string, en: string) => string;
  token: string;
};

function ProtocolFields({ type, settings, json, onChange, tx, token }: ProtocolFieldsProps) {
  const current = useMemo(() => { try { return parseObject(json, "invalid"); } catch { return settings; } }, [json, settings]);
  const value = (path: string, fallback: unknown = "") => readPath(current, path, fallback);
  const textValue = (path: string, fallback = "") => String(value(path, fallback));
  const numberValue = (path: string, fallback = 0) => Number(value(path, fallback));
  const boolValue = (path: string, fallback = false) => Boolean(value(path, fallback));
  const tlsObjectPath = ["hysteria", "tuic", "anytls"].includes(type) ? "tls" : "tls_settings";
  const supportsTransport = ["vmess", "vless", "trojan"].includes(type);
  const supportsTlsSettings = ["vmess", "vless", "trojan", "socks", "naive", "http", "hysteria", "tuic", "anytls"].includes(type);
  const supportsMux = ["vmess", "vless", "trojan", "mieru"].includes(type);
  const tlsMode = numberValue("tls", type === "trojan" || type === "naive" ? 1 : 0);
  const showTlsDetails = supportsTlsSettings && (tlsMode > 0 || ["hysteria", "tuic", "anytls"].includes(type));
  const [echBusy, setEchBusy] = useState(false);
  const [echError, setEchError] = useState("");
  const [realityBusy, setRealityBusy] = useState(false);
  const [realityError, setRealityError] = useState("");

  async function makeEch() {
    setEchBusy(true); setEchError("");
    try {
      const name = textValue(`${tlsObjectPath}.ech.query_server_name`, textValue(`${tlsObjectPath}.server_name`, "ech.example.com")) || "ech.example.com";
      const result = await generateEchKey(token, name);
      onChange(`${tlsObjectPath}.ech`, {
        ...asObject(value(`${tlsObjectPath}.ech`, {})), enabled: true,
        query_server_name: name, key: result.key, config: result.config
      });
    } catch (caught) { setEchError(caught instanceof ApiError ? caught.message : tx("ECH 密钥生成失败", "ECH generation failed")); }
    finally { setEchBusy(false); }
  }

  async function makeRealityKeyPair() {
    setRealityBusy(true); setRealityError("");
    try {
      const pair = await crypto.subtle.generateKey({ name: "X25519" }, true, ["deriveBits"]);
      const [publicJwk, privateJwk] = await Promise.all([
        crypto.subtle.exportKey("jwk", pair.publicKey), crypto.subtle.exportKey("jwk", pair.privateKey)
      ]);
      onChange("reality_settings", {
        ...asObject(value("reality_settings", {})),
        public_key: publicJwk.x ?? "", private_key: privateJwk.d ?? ""
      });
    } catch { setRealityError(tx("当前浏览器无法生成 X25519 密钥对", "This browser cannot generate an X25519 key pair")); }
    finally { setRealityBusy(false); }
  }

  function makeShortId() {
    const bytes = crypto.getRandomValues(new Uint8Array(8));
    onChange("reality_settings.short_id", Array.from(bytes, (item) => item.toString(16).padStart(2, "0")).join(""));
  }

  return <div className="node-protocol-fields">
    {type === "shadowsocks" && <><InputField label={tx("加密算法", "Cipher")} hint={tx("可选择预设或输入 xboard-node 支持的自定义算法", "Choose a preset or enter any cipher supported by xboard-node")}><input list="node-cipher-options" value={textValue("cipher")} onChange={(event) => onChange("cipher", event.target.value)} /><datalist id="node-cipher-options">{cipherOptions.map((cipher) => <option key={cipher} value={cipher} />)}</datalist></InputField>
      <InputField label={tx("插件", "Plugin")}><select value={textValue("plugin")} onChange={(event) => onChange("plugin", event.target.value)}><option value="">{tx("无", "None")}</option><option value="obfs-local">simple-obfs</option><option value="v2ray-plugin">v2ray-plugin</option><option value="gost-plugin">gost-plugin</option><option value="shadow-tls">ShadowTLS</option><option value="restls">Restls</option><option value="kcptun">kcptun</option></select></InputField>
      <InputField label={tx("插件选项", "Plugin options")} wide><input placeholder="key=value;host=example.com" value={textValue("plugin_opts")} onChange={(event) => onChange("plugin_opts", event.target.value)} /></InputField>
      <InputField label={tx("混淆", "Obfuscation")}><select value={textValue("obfs")} onChange={(event) => onChange("obfs", event.target.value)}><option value="">{tx("无", "None")}</option><option value="http">HTTP</option></select></InputField>
      {textValue("obfs") === "http" && <><InputField label={tx("混淆路径", "Obfuscation path")}><input value={textValue("obfs_settings.path", "/")} onChange={(event) => onChange("obfs_settings.path", event.target.value)} /></InputField><InputField label={tx("混淆 Host", "Obfuscation host")}><input value={textValue("obfs_settings.host")} onChange={(event) => onChange("obfs_settings.host", event.target.value)} /></InputField></>}
      <InputField label={tx("客户端指纹", "Client fingerprint")}><select value={textValue("client_fingerprint")} onChange={(event) => onChange("client_fingerprint", event.target.value)}><option value="">{tx("默认", "Default")}</option>{fingerprintOptions.map((item) => <option key={item}>{item}</option>)}</select></InputField></>}

    {["vmess", "vless", "trojan", "socks", "naive", "http"].includes(type) && <InputField label={tx("TLS / 安全模式", "TLS / security mode")}><select value={tlsMode} onChange={(event) => onChange("tls", Number(event.target.value))}><option value={0}>{tx("关闭", "Disabled")}</option><option value={1}>TLS</option>{["vless", "trojan"].includes(type) && <option value={2}>Reality</option>}</select></InputField>}

    {supportsTransport && <><InputField label={tx("传输协议", "Transport")}><select value={textValue("network", "tcp")} onChange={(event) => onChange("network", event.target.value)}>{transportOptions.map((item) => <option key={item}>{item}</option>)}</select></InputField>
      <NetworkFields boolValue={boolValue} network={textValue("network", "tcp")} onChange={onChange} tx={tx} value={textValue} /></>}

    {type === "vless" && <><InputField label="Flow"><select value={textValue("flow")} onChange={(event) => onChange("flow", event.target.value)}><option value="">{tx("无", "None")}</option><option value="xtls-rprx-vision">xtls-rprx-vision</option></select></InputField>
      <Toggle checked={boolValue("encryption.enabled")} label={tx("启用 VLESS Encryption", "Enable VLESS encryption")} onChange={(checked) => onChange("encryption.enabled", checked)} />
      {boolValue("encryption.enabled") && <><InputField label={tx("加密", "Encryption")}><input value={textValue("encryption.encryption")} onChange={(event) => onChange("encryption.encryption", event.target.value)} /></InputField><InputField label={tx("解密", "Decryption")}><input value={textValue("encryption.decryption")} onChange={(event) => onChange("encryption.decryption", event.target.value)} /></InputField></>}</>}

    {type === "hysteria" && <><InputField label={tx("版本", "Version")}><select value={numberValue("version", 2)} onChange={(event) => onChange("version", Number(event.target.value))}><option value={1}>Hysteria 1</option><option value={2}>Hysteria 2</option></select></InputField><InputField label="ALPN"><input value={textValue("alpn", "h3")} onChange={(event) => onChange("alpn", event.target.value)} /></InputField>
      <InputField label={tx("上传带宽 Mbps", "Upload bandwidth Mbps")}><input min="0" type="number" value={numberValue("bandwidth.up", 100)} onChange={(event) => onChange("bandwidth.up", Number(event.target.value))} /></InputField><InputField label={tx("下载带宽 Mbps", "Download bandwidth Mbps")}><input min="0" type="number" value={numberValue("bandwidth.down", 100)} onChange={(event) => onChange("bandwidth.down", Number(event.target.value))} /></InputField>
      <InputField label={tx("端口跳跃间隔（秒）", "Port hop interval (s)")}><input min="0" type="number" value={numberValue("hop_interval", 30)} onChange={(event) => onChange("hop_interval", Number(event.target.value))} /></InputField><Toggle checked={boolValue("obfs.open")} label={tx("启用混淆", "Enable obfuscation")} onChange={(checked) => onChange("obfs.open", checked)} />
      {boolValue("obfs.open") && <><InputField label={tx("混淆类型", "Obfuscation type")}><input value={textValue("obfs.type", "salamander")} onChange={(event) => onChange("obfs.type", event.target.value)} /></InputField><InputField label={tx("混淆密码", "Obfuscation password")}><input value={textValue("obfs.password")} onChange={(event) => onChange("obfs.password", event.target.value)} /></InputField></>}</>}

    {type === "tuic" && <><InputField label={tx("版本", "Version")}><select value={numberValue("version", 5)} onChange={(event) => onChange("version", Number(event.target.value))}><option value={4}>TUIC v4</option><option value={5}>TUIC v5</option></select></InputField><InputField label={tx("拥塞控制", "Congestion control")}><select value={textValue("congestion_control", "bbr")} onChange={(event) => onChange("congestion_control", event.target.value)}><option value="bbr">BBR</option><option value="cubic">Cubic</option><option value="new_reno">New Reno</option></select></InputField>
      <InputField label={tx("密码", "Password")}><input value={textValue("password")} onChange={(event) => onChange("password", event.target.value)} /></InputField><InputField label="ALPN"><input value={(value("alpn", ["h3"]) as unknown[]).join(", ")} onChange={(event) => onChange("alpn", event.target.value.split(",").map((item) => item.trim()).filter(Boolean))} /></InputField><InputField label={tx("UDP 中继模式", "UDP relay mode")}><select value={textValue("udp_relay_mode", "native")} onChange={(event) => onChange("udp_relay_mode", event.target.value)}><option value="native">native</option><option value="quic">quic</option></select></InputField></>}

    {type === "socks" && <InputField label={tx("SOCKS 版本", "SOCKS version")}><select value={numberValue("version", 5)} onChange={(event) => onChange("version", Number(event.target.value))}><option value={4}>SOCKS4</option><option value={5}>SOCKS5</option></select></InputField>}

    {type === "mieru" && <><InputField label={tx("传输", "Transport")}><select value={textValue("transport", "TCP")} onChange={(event) => onChange("transport", event.target.value)}><option>TCP</option><option>UDP</option></select></InputField><InputField label={tx("流量模式（Base64）", "Traffic pattern (Base64)")} wide><div className="node-input-action"><input value={textValue("traffic_pattern")} onChange={(event) => onChange("traffic_pattern", event.target.value)} /><button onClick={() => { const bytes = crypto.getRandomValues(new Uint8Array(32)); onChange("traffic_pattern", btoa(String.fromCharCode(...bytes))); }} type="button">{tx("随机生成", "Generate")}</button></div></InputField></>}

    {type === "anytls" && <InputField label={tx("填充方案（每行一条）", "Padding scheme (one rule per line)")} wide><textarea value={(value("padding_scheme", []) as unknown[]).join("\n")} onChange={(event) => onChange("padding_scheme", event.target.value.split("\n").map((item) => item.trim()).filter(Boolean))} /></InputField>}

    {showTlsDetails && <div className="node-protocol-subsection"><h4>TLS / ECH</h4><InputField label="SNI / Server Name"><input value={textValue(`${tlsObjectPath}.server_name`)} onChange={(event) => onChange(`${tlsObjectPath}.server_name`, event.target.value)} /></InputField>
      <Toggle checked={boolValue(`${tlsObjectPath}.allow_insecure`)} label={tx("允许不安全证书", "Allow insecure certificate")} onChange={(checked) => onChange(`${tlsObjectPath}.allow_insecure`, checked)} />
      <Toggle checked={boolValue(`${tlsObjectPath}.ech.enabled`)} label={tx("启用 ECH", "Enable ECH")} onChange={(checked) => onChange(`${tlsObjectPath}.ech.enabled`, checked)} />
      {boolValue(`${tlsObjectPath}.ech.enabled`) && <><InputField label={tx("ECH 查询服务器名称", "ECH query server name")}><input value={textValue(`${tlsObjectPath}.ech.query_server_name`)} onChange={(event) => onChange(`${tlsObjectPath}.ech.query_server_name`, event.target.value)} /></InputField><button className="node-inline-button" disabled={echBusy} onClick={() => void makeEch()} type="button">{echBusy ? tx("生成中…", "Generating...") : tx("生成 ECH 密钥", "Generate ECH keys")}</button>
        <InputField label="ECH Config Path"><input value={textValue(`${tlsObjectPath}.ech.config_path`)} onChange={(event) => onChange(`${tlsObjectPath}.ech.config_path`, event.target.value)} /></InputField><InputField label="ECH Key Path"><input value={textValue(`${tlsObjectPath}.ech.key_path`)} onChange={(event) => onChange(`${tlsObjectPath}.ech.key_path`, event.target.value)} /></InputField>
        <InputField label="ECH Config" wide><textarea value={textValue(`${tlsObjectPath}.ech.config`)} onChange={(event) => onChange(`${tlsObjectPath}.ech.config`, event.target.value)} /></InputField><InputField label="ECH Key" wide><textarea value={textValue(`${tlsObjectPath}.ech.key`)} onChange={(event) => onChange(`${tlsObjectPath}.ech.key`, event.target.value)} /></InputField>{echError && <p className="admin-operation-error">{echError}</p>}</>}
    </div>}

    {["vless", "trojan"].includes(type) && tlsMode === 2 && <div className="node-protocol-subsection"><h4>Reality</h4><InputField label={tx("伪装站点", "Destination host")}><input value={textValue("reality_settings.server_name")} onChange={(event) => onChange("reality_settings.server_name", event.target.value)} /></InputField><InputField label={tx("伪装端口", "Destination port")}><input min="1" type="number" value={numberValue("reality_settings.server_port", 443)} onChange={(event) => onChange("reality_settings.server_port", Number(event.target.value))} /></InputField>
      <InputField label={tx("私钥", "Private key")}><input value={textValue("reality_settings.private_key")} onChange={(event) => onChange("reality_settings.private_key", event.target.value)} /></InputField><InputField label={tx("公钥", "Public key")}><input value={textValue("reality_settings.public_key")} onChange={(event) => onChange("reality_settings.public_key", event.target.value)} /></InputField>
      <button className="node-inline-button" disabled={realityBusy} onClick={() => void makeRealityKeyPair()} type="button">{realityBusy ? tx("生成中…", "Generating...") : tx("生成 X25519 密钥对", "Generate X25519 key pair")}</button>
      <InputField label="Short ID"><div className="node-input-action"><input value={textValue("reality_settings.short_id")} onChange={(event) => onChange("reality_settings.short_id", event.target.value)} /><button onClick={makeShortId} type="button">{tx("生成", "Generate")}</button></div></InputField>{realityError && <p className="admin-operation-error">{realityError}</p>}</div>}

    {type === "trojan" && <><InputField label={tx("客户端 SNI", "Client SNI")}><input value={textValue("server_name")} onChange={(event) => onChange("server_name", event.target.value)} /></InputField><Toggle checked={boolValue("allow_insecure")} label={tx("客户端允许不安全证书", "Client allows insecure certificate")} onChange={(checked) => onChange("allow_insecure", checked)} /></>}

    {["vmess", "vless", "trojan"].includes(type) && <div className="node-protocol-subsection"><h4>uTLS</h4><Toggle checked={boolValue("utls.enabled")} label={tx("启用 uTLS 指纹", "Enable uTLS fingerprint")} onChange={(checked) => onChange("utls.enabled", checked)} />{boolValue("utls.enabled") && <InputField label={tx("指纹", "Fingerprint")}><select value={textValue("utls.fingerprint", "chrome")} onChange={(event) => onChange("utls.fingerprint", event.target.value)}>{fingerprintOptions.map((item) => <option key={item}>{item}</option>)}</select></InputField>}</div>}

    {supportsMux && <MultiplexFields boolValue={boolValue} numberValue={numberValue} onChange={onChange} textValue={textValue} tx={tx} />}
  </div>;
}

function NetworkFields({ network, value, boolValue, onChange, tx }: { network: string; value: (path: string, fallback?: string) => string; boolValue: (path: string, fallback?: boolean) => boolean; onChange: (path: string, value: unknown) => void; tx: (zh: string, en: string) => string }) {
  if (network === "grpc") return <><InputField label={tx("Service Name", "Service name")}><input value={value("network_settings.service_name")} onChange={(event) => onChange("network_settings.service_name", event.target.value)} /></InputField><Toggle checked={boolValue("network_settings.multi_mode")} label={tx("多模式", "Multi mode")} onChange={(checked) => onChange("network_settings.multi_mode", checked)} /></>;
  if (["ws", "httpupgrade", "xhttp", "tcp-http", "h2"].includes(network)) return <><InputField label={tx("路径", "Path")}><input value={value("network_settings.path", "/")} onChange={(event) => onChange("network_settings.path", event.target.value)} /></InputField><InputField label="Host"><input value={value("network_settings.host")} onChange={(event) => onChange("network_settings.host", event.target.value)} /></InputField>{network === "xhttp" && <InputField label="Mode"><select value={value("network_settings.mode", "auto")} onChange={(event) => onChange("network_settings.mode", event.target.value)}><option value="auto">auto</option><option value="packet-up">packet-up</option><option value="stream-up">stream-up</option><option value="stream-one">stream-one</option></select></InputField>}</>;
  return null;
}

function MultiplexFields({ boolValue, numberValue, textValue, onChange, tx }: { boolValue: (path: string, fallback?: boolean) => boolean; numberValue: (path: string, fallback?: number) => number; textValue: (path: string, fallback?: string) => string; onChange: (path: string, value: unknown) => void; tx: (zh: string, en: string) => string }) {
  return <div className="node-protocol-subsection"><h4>{tx("多路复用", "Multiplex")}</h4><Toggle checked={boolValue("multiplex.enabled")} label={tx("启用多路复用", "Enable multiplex")} onChange={(checked) => onChange("multiplex.enabled", checked)} />{boolValue("multiplex.enabled") && <><InputField label={tx("协议", "Protocol")}><select value={textValue("multiplex.protocol", "smux")} onChange={(event) => onChange("multiplex.protocol", event.target.value)}><option value="smux">smux</option><option value="yamux">yamux</option><option value="h2mux">h2mux</option></select></InputField><InputField label={tx("最大连接数", "Max connections")}><input min="0" type="number" value={numberValue("multiplex.max_connections", 4)} onChange={(event) => onChange("multiplex.max_connections", Number(event.target.value))} /></InputField><InputField label={tx("最小流数", "Min streams")}><input min="0" type="number" value={numberValue("multiplex.min_streams", 4)} onChange={(event) => onChange("multiplex.min_streams", Number(event.target.value))} /></InputField><InputField label={tx("最大流数", "Max streams")}><input min="0" type="number" value={numberValue("multiplex.max_streams", 0)} onChange={(event) => onChange("multiplex.max_streams", Number(event.target.value))} /></InputField><Toggle checked={boolValue("multiplex.padding")} label={tx("启用填充", "Enable padding")} onChange={(checked) => onChange("multiplex.padding", checked)} /><Toggle checked={boolValue("multiplex.brutal.enabled")} label="TCP Brutal" onChange={(checked) => onChange("multiplex.brutal.enabled", checked)} />{boolValue("multiplex.brutal.enabled") && <><InputField label="Brutal Up Mbps"><input min="0" type="number" value={numberValue("multiplex.brutal.up_mbps", 100)} onChange={(event) => onChange("multiplex.brutal.up_mbps", Number(event.target.value))} /></InputField><InputField label="Brutal Down Mbps"><input min="0" type="number" value={numberValue("multiplex.brutal.down_mbps", 100)} onChange={(event) => onChange("multiplex.brutal.down_mbps", Number(event.target.value))} /></InputField></>}</>}</div>;
}

function CertFields({ json, onChange, tx }: { json: string; onChange: (value: string) => void; tx: (zh: string, en: string) => string }) {
  let cert: Record<string, unknown> = {};
  try { cert = parseObject(json, "invalid"); } catch { /* show invalid JSON below */ }
  const mode = String(cert.cert_mode ?? cert.mode ?? "none");
  const update = (key: string, value: unknown) => onChange(JSON.stringify({ ...cert, [key]: value, ...(key === "cert_mode" ? { mode: undefined } : {}) }, (_key, item) => item === undefined ? undefined : item, 2));
  return <div className="node-cert-fields"><InputField label={tx("证书模式", "Certificate mode")}><select value={mode} onChange={(event) => update("cert_mode", event.target.value)}><option value="none">{tx("不配置", "None")}</option><option value="self">{tx("自签名", "Self-signed")}</option><option value="http">HTTP-01</option><option value="dns">DNS-01</option><option value="content">{tx("推送证书内容", "Certificate content")}</option></select></InputField>
    {mode !== "none" && <InputField label={tx("证书域名", "Certificate domain")}><input value={String(cert.domain ?? "")} onChange={(event) => update("domain", event.target.value)} /></InputField>}
    {["http", "dns"].includes(mode) && <InputField label={tx("通知邮箱", "Notification email")}><input type="email" value={String(cert.email ?? "")} onChange={(event) => update("email", event.target.value)} /></InputField>}
    {mode === "http" && <InputField label={tx("认证端口", "Challenge port")}><input min="1" type="number" value={Number(cert.http_port ?? 80)} onChange={(event) => update("http_port", Number(event.target.value))} /></InputField>}
    {mode === "dns" && <><InputField label={tx("DNS 提供商", "DNS provider")}><input value={String(cert.dns_provider ?? "cloudflare")} onChange={(event) => update("dns_provider", event.target.value)} /></InputField><InputField label={tx("DNS 环境变量（每行 KEY=VALUE）", "DNS environment (KEY=VALUE per line)")} wide><textarea value={String(cert.dns_env ?? "")} onChange={(event) => update("dns_env", event.target.value)} /></InputField></>}
    {mode === "content" && <><InputField label={tx("证书内容", "Certificate content")} wide><textarea value={String(cert.cert_content ?? "")} onChange={(event) => update("cert_content", event.target.value)} /></InputField><InputField label={tx("私钥内容", "Private key content")} wide><textarea value={String(cert.key_content ?? "")} onChange={(event) => update("key_content", event.target.value)} /></InputField></>}
    <details className="node-advanced-json node-cert-json"><summary>{tx("编辑证书 JSON", "Edit certificate JSON")}</summary><textarea spellCheck={false} value={json} onChange={(event) => onChange(event.target.value)} /></details></div>;
}
