import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";

import { listMachines } from "../admin/machineManagementApi";
import {
  NODE_PROTOCOLS, copyNode, deleteNode, listNodes, resetNodeTraffic,
  saveNode, updateNode, type ManagedNode, type NodeDraft, type NodeProtocol
} from "../admin/nodeManagementApi";
import { AdminShell } from "../components/AdminShell";
import { ApiError } from "../lib/http";
import { useAdminAuthStore } from "../store/adminAuth";
import { useAdminPreferences } from "../store/adminPreferences";

const copy = {
  "zh-CN": {
    eyebrow: "节点管理", title: "节点管理", description: "配置代理协议、监听端口及节点与 xboard-node 机器的绑定关系。",
    create: "添加节点", name: "名称", protocol: "协议", machine: "服务器", address: "连接地址",
    traffic: "流量", status: "状态", actions: "操作", unbound: "未绑定", enabled: "启用", disabled: "停用",
    visible: "显示", hidden: "隐藏", edit: "编辑", duplicate: "复制", reset: "重置流量", remove: "删除",
    empty: "还没有节点。", loading: "正在加载节点…", createTitle: "添加节点", editTitle: "编辑节点",
    host: "公网域名 / IP", port: "公网端口", serverPort: "监听端口", rate: "倍率", quota: "流量上限（字节）",
    settings: "协议设置（JSON）", settingsHint: "字段直接传递给 xboard-node，例如 cipher、network、tls_settings。",
    show: "在用户端显示", run: "允许节点运行", cancel: "取消", save: "保存", invalidJson: "协议设置必须是 JSON 对象",
    operationFailed: "操作失败", removeConfirm: "确定删除这个节点吗？", resetConfirm: "确定清零这个节点的流量统计吗？"
  },
  "en-US": {
    eyebrow: "Infrastructure", title: "Nodes", description: "Configure proxy protocols, ports, and xboard-node machine bindings.",
    create: "Add node", name: "Name", protocol: "Protocol", machine: "Machine", address: "Address",
    traffic: "Traffic", status: "Status", actions: "Actions", unbound: "Unbound", enabled: "Enabled", disabled: "Disabled",
    visible: "Visible", hidden: "Hidden", edit: "Edit", duplicate: "Copy", reset: "Reset traffic", remove: "Delete",
    empty: "No nodes yet.", loading: "Loading nodes…", createTitle: "Add node", editTitle: "Edit node",
    host: "Public host / IP", port: "Public port", serverPort: "Listen port", rate: "Rate", quota: "Traffic limit (bytes)",
    settings: "Protocol settings (JSON)", settingsHint: "Fields pass directly to xboard-node, such as cipher, network, and tls_settings.",
    show: "Show to users", run: "Allow node to run", cancel: "Cancel", save: "Save", invalidJson: "Protocol settings must be a JSON object",
    operationFailed: "Operation failed", removeConfirm: "Delete this node?", resetConfirm: "Reset traffic counters for this node?"
  }
};

const emptyDraft: NodeDraft = {
  type: "shadowsocks", name: "", machine_id: null, host: "", port: null,
  server_port: 443, rate: 1, transfer_enable: 0, show: true, enabled: true,
  protocol_settings: { network: "tcp", cipher: "2022-blake3-aes-128-gcm" },
  group_ids: [], route_ids: [], tags: [], custom_outbounds: [], custom_routes: []
};

function formatBytes(value: number) {
  if (!value) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1);
  return `${(value / 1024 ** index).toFixed(index > 2 ? 2 : 1)} ${units[index]}`;
}

export function AdminNodesPage() {
  const language = useAdminPreferences((state) => state.language);
  const token = useAdminAuthStore((state) => state.accessToken)!;
  const text = copy[language];
  const client = useQueryClient();
  const [editing, setEditing] = useState<ManagedNode | null | undefined>();
  const [draft, setDraft] = useState<NodeDraft>(emptyDraft);
  const [settings, setSettings] = useState(JSON.stringify(emptyDraft.protocol_settings, null, 2));
  const [error, setError] = useState("");

  const nodesQuery = useQuery({ queryKey: ["admin", "nodes"], queryFn: () => listNodes(token) });
  const machinesQuery = useQuery({ queryKey: ["admin", "machines"], queryFn: () => listMachines(token) });
  const refresh = () => client.invalidateQueries({ queryKey: ["admin", "nodes"] });
  const saveMutation = useMutation({ mutationFn: (value: NodeDraft) => saveNode(token, value), onSuccess: refresh });

  function openCreate() {
    setDraft({ ...emptyDraft, protocol_settings: { ...emptyDraft.protocol_settings } });
    setSettings(JSON.stringify(emptyDraft.protocol_settings, null, 2));
    setEditing(null); setError("");
  }

  function openEdit(node: ManagedNode) {
    setDraft({
      id: node.id, type: node.type, name: node.name, machine_id: node.machine_id,
      host: node.host ?? "", port: node.port, server_port: node.server_port,
      rate: node.rate, transfer_enable: node.transfer_enable, show: node.show,
      enabled: node.enabled, protocol_settings: node.protocol_settings,
      group_ids: node.group_ids, route_ids: node.route_ids, tags: node.tags,
      custom_outbounds: node.custom_outbounds, custom_routes: node.custom_routes
    });
    setSettings(JSON.stringify(node.protocol_settings, null, 2));
    setEditing(node); setError("");
  }

  async function submit(event: FormEvent) {
    event.preventDefault(); setError("");
    try {
      const parsed = JSON.parse(settings) as unknown;
      if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") throw new Error();
      await saveMutation.mutateAsync({ ...draft, protocol_settings: parsed as Record<string, unknown> });
      setEditing(undefined);
    } catch (caught) {
      setError(caught instanceof SyntaxError || caught instanceof Error && !ApiError.prototype.isPrototypeOf(caught)
        ? text.invalidJson : caught instanceof ApiError ? caught.message : text.operationFailed);
    }
  }

  async function action(work: () => Promise<unknown>) {
    setError("");
    try { await work(); await refresh(); }
    catch (caught) { setError(caught instanceof ApiError ? caught.message : text.operationFailed); }
  }

  return <AdminShell>
    <header className="admin-page-heading admin-page-heading-action"><div><p>{text.eyebrow}</p><h1>{text.title}</h1><span>{text.description}</span></div>
      <button className="admin-primary-button" onClick={openCreate} type="button">+ {text.create}</button></header>
    {error && <p className="admin-operation-error">{error}</p>}
    <section className="admin-card admin-table-wrap">
      {nodesQuery.isPending ? <p className="admin-table-empty">{text.loading}</p> : !nodesQuery.data?.length ? <p className="admin-table-empty">{text.empty}</p> :
        <table className="admin-table"><thead><tr><th>{text.name}</th><th>{text.protocol}</th><th>{text.machine}</th><th>{text.address}</th><th>{text.traffic}</th><th>{text.status}</th><th>{text.actions}</th></tr></thead>
          <tbody>{nodesQuery.data.map((node) => <tr key={node.id}>
            <td><strong>{node.name}</strong><small>#{node.id} · {node.rate}x</small></td><td>{node.type}</td>
            <td>{machinesQuery.data?.find((machine) => machine.id === node.machine_id)?.name ?? text.unbound}</td>
            <td>{node.host || "—"}:{node.port ?? node.server_port}</td><td>↑ {formatBytes(node.u)} · ↓ {formatBytes(node.d)}</td>
            <td><div className="admin-node-states"><button type="button" className={node.enabled ? "admin-state-chip success" : "admin-state-chip"}
              onClick={() => void action(() => updateNode(token, node.id, { enabled: !node.enabled }))}>{node.enabled ? text.enabled : text.disabled}</button>
              <button type="button" className={node.show ? "admin-state-chip success" : "admin-state-chip"}
                onClick={() => void action(() => updateNode(token, node.id, { show: !node.show }))}>{node.show ? text.visible : text.hidden}</button></div></td>
            <td><div className="admin-table-action machine-actions"><button onClick={() => openEdit(node)} type="button">{text.edit}</button>
              <button onClick={() => void action(() => copyNode(token, node.id))} type="button">{text.duplicate}</button>
              <button onClick={() => window.confirm(text.resetConfirm) && void action(() => resetNodeTraffic(token, node.id))} type="button">{text.reset}</button>
              <button className="danger" onClick={() => window.confirm(text.removeConfirm) && void action(() => deleteNode(token, node.id))} type="button">{text.remove}</button></div></td>
          </tr>)}</tbody></table>}
    </section>
    {editing !== undefined && <div className="admin-modal-backdrop" role="presentation"><form className="admin-modal admin-node-modal" onSubmit={(event) => void submit(event)}>
      <header><h2>{editing ? text.editTitle : text.createTitle}</h2><button onClick={() => setEditing(undefined)} type="button">×</button></header>
      <div className="admin-node-form">
        <label>{text.name}<input required value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.target.value })} /></label>
        <label>{text.protocol}<select value={draft.type} onChange={(event) => setDraft({ ...draft, type: event.target.value as NodeProtocol })}>{NODE_PROTOCOLS.map((item) => <option key={item}>{item}</option>)}</select></label>
        <label>{text.machine}<select value={draft.machine_id ?? ""} onChange={(event) => setDraft({ ...draft, machine_id: event.target.value ? Number(event.target.value) : null })}><option value="">{text.unbound}</option>{machinesQuery.data?.map((machine) => <option key={machine.id} value={machine.id}>{machine.name}</option>)}</select></label>
        <label>{text.host}<input value={draft.host} onChange={(event) => setDraft({ ...draft, host: event.target.value })} /></label>
        <label>{text.port}<input min={1} max={65535} type="number" value={draft.port ?? ""} onChange={(event) => setDraft({ ...draft, port: event.target.value ? Number(event.target.value) : null })} /></label>
        <label>{text.serverPort}<input required min={1} max={65535} type="number" value={draft.server_port} onChange={(event) => setDraft({ ...draft, server_port: Number(event.target.value) })} /></label>
        <label>{text.rate}<input min={0} step="0.01" type="number" value={draft.rate} onChange={(event) => setDraft({ ...draft, rate: Number(event.target.value) })} /></label>
        <label>{text.quota}<input min={0} type="number" value={draft.transfer_enable} onChange={(event) => setDraft({ ...draft, transfer_enable: Number(event.target.value) })} /></label>
        <label className="admin-node-json">{text.settings}<textarea spellCheck={false} value={settings} onChange={(event) => setSettings(event.target.value)} /><small>{text.settingsHint}</small></label>
        <label className="machine-check"><input checked={draft.show} type="checkbox" onChange={(event) => setDraft({ ...draft, show: event.target.checked })} />{text.show}</label>
        <label className="machine-check"><input checked={draft.enabled} type="checkbox" onChange={(event) => setDraft({ ...draft, enabled: event.target.checked })} />{text.run}</label>
        {error && <p className="admin-operation-error">{error}</p>}
      </div><footer><button onClick={() => setEditing(undefined)} type="button">{text.cancel}</button><button className="primary" disabled={saveMutation.isPending} type="submit">{text.save}</button></footer>
    </form></div>}
  </AdminShell>;
}
