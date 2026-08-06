import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState, type FormEvent } from "react";

import {
  deleteNodeRoute,
  EDITABLE_ROUTE_ACTIONS,
  listNodeRoutes,
  saveNodeRoute,
  type EditableRouteAction,
  type ManagedNodeRoute,
  type NodeRouteDraft
} from "../admin/routeManagementApi";
import { AdminShell } from "../components/AdminShell";
import { ApiError } from "../lib/http";
import { useAdminAuthStore } from "../store/adminAuth";
import { useAdminPreferences } from "../store/adminPreferences";

type SortKey = "id" | "remarks" | "action";
type SortDirection = "asc" | "desc";

const copy = {
  "zh-CN": {
    eyebrow: "节点管理", title: "路由管理", description: "管理所有路由规则，包括添加、删除、编辑等操作。",
    add: "添加路由", search: "搜索路由...", id: "组ID", remarks: "备注", actionValue: "动作值", action: "动作",
    operations: "操作", edit: "编辑路由", remove: "删除", loading: "正在加载路由…", empty: "还没有路由规则。",
    noResult: "没有符合搜索条件的路由。", createTitle: "创建路由", editTitle: "编辑路由", remarksPlaceholder: "请输入备注",
    match: "匹配规则", matchPlaceholder: "example.com\n*.example.com", matchHint: "每行一条规则；保存时会去除空行和重复项。",
    outbound: "转发标签 (Outbound Tag)", outboundPlaceholder: "请输入转发标签", cancel: "取消", confirm: "确认", saving: "保存中…",
    removeConfirm: "确定删除这条路由吗？节点中的关联会同时清除。", operationFailed: "操作失败", remarksRequired: "请输入备注。",
    matchRequired: "请至少输入一条匹配规则。", outboundRequired: "转发动作必须填写 Outbound Tag。", unsupported: "该路由动作未在当前项目启用，不能从此页面编辑。",
    block: "禁止访问", direct: "直连", proxy: "转发", rules: "匹配 {count} 条规则", selected: "共 {count} 项", perPage: "每页显示",
    page: "第 {page} / {pages} 页", first: "首页", previous: "上一页", next: "下一页", last: "末页"
  },
  "en-US": {
    eyebrow: "Nodes", title: "Routes", description: "Create, edit, and remove routing rules.",
    add: "Add route", search: "Search routes...", id: "Group ID", remarks: "Remarks", actionValue: "Action value", action: "Action",
    operations: "Actions", edit: "Edit route", remove: "Delete", loading: "Loading routes…", empty: "No route rules yet.",
    noResult: "No routes match your search.", createTitle: "Create route", editTitle: "Edit route", remarksPlaceholder: "Enter remarks",
    match: "Match rules", matchPlaceholder: "example.com\n*.example.com", matchHint: "One rule per line; blank and duplicate lines are removed.",
    outbound: "Outbound Tag", outboundPlaceholder: "Enter an outbound tag", cancel: "Cancel", confirm: "Confirm", saving: "Saving…",
    removeConfirm: "Delete this route? Node references to it will also be removed.", operationFailed: "Operation failed", remarksRequired: "Enter remarks.",
    matchRequired: "Enter at least one match rule.", outboundRequired: "Proxy routes require an outbound tag.", unsupported: "This route action is not enabled in this project and cannot be edited here.",
    block: "Block", direct: "Direct", proxy: "Proxy", rules: "{count} matching rules", selected: "{count} items", perPage: "Per page",
    page: "Page {page} of {pages}", first: "First", previous: "Previous", next: "Next", last: "Last"
  }
};

type RouteForm = {
  remarks: string;
  matchText: string;
  action: EditableRouteAction;
  actionValue: string;
};

const emptyForm: RouteForm = { remarks: "", matchText: "", action: "block", actionValue: "" };

function errorMessage(error: unknown, fallback: string) {
  return error instanceof ApiError ? error.message : fallback;
}

function isEditableAction(action: string): action is EditableRouteAction {
  return (EDITABLE_ROUTE_ACTIONS as readonly string[]).includes(action);
}

export function AdminNodeRoutesPage() {
  const language = useAdminPreferences((state) => state.language);
  const token = useAdminAuthStore((state) => state.accessToken)!;
  const text = copy[language];
  const client = useQueryClient();
  const [search, setSearch] = useState("");
  const [sortKey, setSortKey] = useState<SortKey>("id");
  const [sortDirection, setSortDirection] = useState<SortDirection>("asc");
  const [pageSize, setPageSize] = useState(10);
  const [page, setPage] = useState(1);
  const [editing, setEditing] = useState<ManagedNodeRoute | null | undefined>();
  const [form, setForm] = useState<RouteForm>(emptyForm);
  const [error, setError] = useState("");

  const routesQuery = useQuery({
    queryKey: ["admin", "node-routes"],
    queryFn: () => listNodeRoutes(token)
  });
  const saveMutation = useMutation({
    mutationFn: (draft: NodeRouteDraft) => saveNodeRoute(token, draft),
    onSuccess: () => client.invalidateQueries({ queryKey: ["admin", "node-routes"] })
  });

  const filteredRoutes = useMemo(() => {
    const needle = search.trim().toLocaleLowerCase();
    return [...(routesQuery.data ?? [])]
      .filter((route) => !needle || [route.id, route.remarks, route.action, route.action_value ?? "", ...route.match]
        .some((value) => String(value).toLocaleLowerCase().includes(needle)))
      .sort((left, right) => {
        let result: number;
        if (sortKey === "id") result = left.id - right.id;
        else result = left[sortKey].localeCompare(right[sortKey], language);
        return sortDirection === "asc" ? result : -result;
      });
  }, [language, routesQuery.data, search, sortDirection, sortKey]);

  const pageCount = Math.max(1, Math.ceil(filteredRoutes.length / pageSize));
  const currentPage = Math.min(page, pageCount);
  const visibleRoutes = filteredRoutes.slice((currentPage - 1) * pageSize, currentPage * pageSize);

  function actionLabel(action: string) {
    if (action === "block") return text.block;
    if (action === "direct") return text.direct;
    if (action === "proxy") return text.proxy;
    return action;
  }

  function changeSort(key: SortKey) {
    setPage(1);
    if (sortKey === key) setSortDirection((value) => value === "asc" ? "desc" : "asc");
    else { setSortKey(key); setSortDirection("asc"); }
  }

  function sortLabel(key: SortKey, label: string) {
    return `${label}${sortKey === key ? sortDirection === "asc" ? " ↑" : " ↓" : ""}`;
  }

  function openCreate() {
    setEditing(null);
    setForm(emptyForm);
    setError("");
  }

  function openEdit(route: ManagedNodeRoute) {
    if (!isEditableAction(route.action)) { setError(text.unsupported); return; }
    setEditing(route);
    setForm({ remarks: route.remarks, matchText: route.match.join("\n"), action: route.action, actionValue: route.action_value ?? "" });
    setError("");
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    const remarks = form.remarks.trim();
    const matches = [...new Set(form.matchText.split(/\r?\n/).map((value) => value.trim()).filter(Boolean))];
    const actionValue = form.actionValue.trim();
    if (!remarks) { setError(text.remarksRequired); return; }
    if (!matches.length) { setError(text.matchRequired); return; }
    if (form.action === "proxy" && !actionValue) { setError(text.outboundRequired); return; }
    setError("");
    try {
      await saveMutation.mutateAsync({
        ...(editing ? { id: editing.id } : {}), remarks, match: matches, action: form.action,
        action_value: form.action === "proxy" ? actionValue : null
      });
      setEditing(undefined);
    } catch (caught) {
      setError(errorMessage(caught, text.operationFailed));
    }
  }

  async function remove(route: ManagedNodeRoute) {
    if (!window.confirm(text.removeConfirm)) return;
    setError("");
    try {
      await deleteNodeRoute(token, route.id);
      await client.invalidateQueries({ queryKey: ["admin", "node-routes"] });
    } catch (caught) {
      setError(errorMessage(caught, text.operationFailed));
    }
  }

  const loadError = routesQuery.error ? errorMessage(routesQuery.error, text.operationFailed) : "";

  return <AdminShell>
    <header className="admin-page-heading">
      <div><p>{text.eyebrow}</p><h1>{text.title}</h1><span>{text.description}</span></div>
      <button className="plan-primary-button" onClick={openCreate} type="button">+ {text.add}</button>
    </header>
    {editing === undefined && (error || loadError) && <p className="admin-operation-error">{error || loadError}</p>}
    <section className="admin-card" style={{ paddingBottom: 4 }}>
      <div style={{ display: "flex", gap: 12, padding: "18px 22px 0" }}>
        <input aria-label={text.search} placeholder={text.search} value={search}
          onChange={(event) => { setSearch(event.target.value); setPage(1); }}
          style={{ width: "min(380px, 100%)", padding: "10px 13px", border: "1px solid #dfe5ee", borderRadius: 9, font: "inherit" }} />
      </div>
      <div className="admin-table-wrap">
        {routesQuery.isPending ? <p className="admin-table-empty">{text.loading}</p> : !routesQuery.data?.length ? <p className="admin-table-empty">{text.empty}</p> : !visibleRoutes.length ? <p className="admin-table-empty">{text.noResult}</p> :
          <table className="admin-table"><thead><tr>
            <th><button type="button" onClick={() => changeSort("id")} style={{ border: 0, padding: 0, background: "transparent", color: "inherit", font: "inherit", fontWeight: "inherit", cursor: "pointer" }}>{sortLabel("id", text.id)}</button></th>
            <th><button type="button" onClick={() => changeSort("remarks")} style={{ border: 0, padding: 0, background: "transparent", color: "inherit", font: "inherit", fontWeight: "inherit", cursor: "pointer" }}>{sortLabel("remarks", text.remarks)}</button></th>
            <th>{text.actionValue}</th>
            <th><button type="button" onClick={() => changeSort("action")} style={{ border: 0, padding: 0, background: "transparent", color: "inherit", font: "inherit", fontWeight: "inherit", cursor: "pointer" }}>{sortLabel("action", text.action)}</button></th>
            <th>{text.operations}</th>
          </tr></thead><tbody>{visibleRoutes.map((route) => <tr key={route.id}>
            <td>{route.id}</td><td><strong>{route.remarks}</strong></td>
            <td>{route.action_value || "—"}</td>
            <td>{actionLabel(route.action)}</td>
            <td><div className="machine-actions"><button onClick={() => openEdit(route)} type="button">{text.edit}</button>
              <button className="danger" onClick={() => void remove(route)} type="button">{text.remove}</button></div></td>
          </tr>)}</tbody></table>}
      </div>
      <div className="admin-pagination">
        <span>{text.selected.replace("{count}", String(filteredRoutes.length))}</span>
        <label>{text.perPage}<select value={pageSize} onChange={(event) => { setPageSize(Number(event.target.value)); setPage(1); }}>
          {[10, 20, 50].map((size) => <option key={size} value={size}>{size}</option>)}
        </select></label>
        <span>{text.page.replace("{page}", String(currentPage)).replace("{pages}", String(pageCount))}</span>
        <div><button aria-label={text.first} disabled={currentPage === 1} onClick={() => setPage(1)} type="button">«</button>
          <button aria-label={text.previous} disabled={currentPage === 1} onClick={() => setPage(currentPage - 1)} type="button">‹</button>
          <button aria-label={text.next} disabled={currentPage === pageCount} onClick={() => setPage(currentPage + 1)} type="button">›</button>
          <button aria-label={text.last} disabled={currentPage === pageCount} onClick={() => setPage(pageCount)} type="button">»</button></div>
      </div>
    </section>
    {editing !== undefined && <div className="admin-modal-backdrop" role="presentation">
      <form className="admin-modal machine-modal" onSubmit={(event) => void submit(event)}>
        <header><h2>{editing ? text.editTitle : text.createTitle}</h2><button aria-label={text.cancel} onClick={() => setEditing(undefined)} type="button">×</button></header>
        <div className="admin-node-form">
          <label>{text.remarks}<input autoFocus maxLength={255} placeholder={text.remarksPlaceholder} required value={form.remarks}
            onChange={(event) => setForm({ ...form, remarks: event.target.value })} /></label>
          <label>{text.action}<select value={form.action} onChange={(event) => setForm({ ...form, action: event.target.value as EditableRouteAction, actionValue: event.target.value === "proxy" ? form.actionValue : "" })}>
            {EDITABLE_ROUTE_ACTIONS.map((action) => <option key={action} value={action}>{actionLabel(action)}</option>)}</select></label>
          <label className="admin-node-json">{text.match}<textarea placeholder={text.matchPlaceholder} required spellCheck={false} value={form.matchText}
            onChange={(event) => setForm({ ...form, matchText: event.target.value })} /><small>{text.matchHint}</small></label>
          {form.action === "proxy" && <label className="admin-node-json">{text.outbound}<input placeholder={text.outboundPlaceholder} required value={form.actionValue}
            onChange={(event) => setForm({ ...form, actionValue: event.target.value })} /></label>}
          {error && <p className="admin-operation-error admin-node-json">{error}</p>}
        </div>
        <footer><button onClick={() => setEditing(undefined)} type="button">{text.cancel}</button>
          <button className="primary" disabled={saveMutation.isPending} type="submit">{saveMutation.isPending ? text.saving : text.confirm}</button></footer>
      </form>
    </div>}
  </AdminShell>;
}
