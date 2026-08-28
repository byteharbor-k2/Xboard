import type { AdminLanguage } from "../admin/adminNavigation";

import "./ConfirmBar.css";

export type ConfirmRequest = {
  message: string;
  confirmLabel: string;
  danger?: boolean;
  run: () => Promise<unknown>;
};

type ConfirmBarProps = {
  request: ConfirmRequest;
  busy: boolean;
  language: AdminLanguage;
  onCancel: () => void;
  onConfirm: () => void;
};

/**
 * In-page confirmation for destructive admin actions.
 *
 * Deliberately not window.confirm: a native dialog blocks the whole renderer,
 * which breaks browser-driven testing and gives no room to name the affected
 * record. The banner states exactly what is about to happen instead.
 */
export function ConfirmBar({
  request,
  busy,
  language,
  onCancel,
  onConfirm
}: ConfirmBarProps) {
  const zh = language === "zh-CN";
  const title = request.danger
    ? zh ? "高风险操作" : "Risky operation"
    : zh ? "请确认操作" : "Confirm action";

  return (
    <section
      aria-live="assertive"
      className={request.danger ? "admin-confirmbar danger" : "admin-confirmbar"}
      role="alertdialog"
    >
      <div>
        <strong>{title}</strong>
        <span>{request.message}</span>
      </div>
      <button disabled={busy} onClick={onCancel} type="button">
        {zh ? "取消" : "Cancel"}
      </button>
      <button
        className={request.danger ? "danger" : "primary"}
        disabled={busy}
        onClick={onConfirm}
        type="button"
      >
        {busy ? (zh ? "处理中…" : "Processing...") : request.confirmLabel}
      </button>
    </section>
  );
}
