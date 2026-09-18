import { useEffect, useState } from "react";

import { copyText } from "../lib/clipboard";
import {
  subscriptionClients,
  subscriptionUrlForClient,
  type SubscriptionClient
} from "../lib/subscriptionClients";
import { QrCode } from "./QrCode";

import "./SubscriptionClientDialog.css";

type Locale = "zh-CN" | "en-US";

const copy = {
  "zh-CN": {
    title: "选择客户端",
    description:
      "选择你的代理软件，复制链接或扫描二维码导入。链接本身就是凭据，请勿分享。",
    copyLink: "复制链接",
    copied: "已复制",
    showQr: "二维码",
    hideQr: "收起",
    qrHint: "用客户端扫描此二维码即可导入。",
    close: "关闭"
  },
  "en-US": {
    title: "Choose your client",
    description:
      "Pick your proxy client, then copy the link or scan the QR code. The link is the credential itself — do not share it.",
    copyLink: "Copy link",
    copied: "Copied",
    showQr: "QR code",
    hideQr: "Hide",
    qrHint: "Scan this code with your client to import.",
    close: "Close"
  }
};

type SubscriptionClientDialogProps = {
  /** The bare `/sub/{token}` address; each row adds its own `flag`. */
  baseUrl: string;
  language: Locale;
  onClose: () => void;
};

/**
 * The client chooser behind the dashboard's copy button.
 *
 * The subscription address used to sit on the page in plain text, next to a QR
 * of the same string. That put a live credential on screen for anyone looking
 * over a shoulder or at a screenshot, and it only ever offered one format. Here
 * the address is never rendered — each row copies or draws its own.
 *
 * `plan-editor-backdrop` is reused rather than redefined: it is already this
 * app's shared modal overlay (see OrderDetailPage).
 */
export function SubscriptionClientDialog({
  baseUrl,
  language,
  onClose
}: SubscriptionClientDialogProps) {
  const labels = copy[language];
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [qrId, setQrId] = useState<string | null>(null);

  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        onClose();
      }
    }
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  async function copyFor(client: SubscriptionClient) {
    await copyText(subscriptionUrlForClient(baseUrl, client));
    setCopiedId(client.id);
    // Guard against a stale timer clearing a newer copy's confirmation.
    window.setTimeout(
      () => setCopiedId((current) => (current === client.id ? null : current)),
      1_500
    );
  }

  return (
    <div className="plan-editor-backdrop" onClick={onClose} role="presentation">
      <section
        aria-label={labels.title}
        aria-modal="true"
        className="subscription-client-dialog"
        onClick={(event) => event.stopPropagation()}
        role="dialog"
      >
        <header>
          <div>
            <h2>{labels.title}</h2>
            <p>{labels.description}</p>
          </div>
          <button autoFocus className="text-button" onClick={onClose} type="button">
            {labels.close}
          </button>
        </header>
        <ul className="subscription-client-list">
          {subscriptionClients.map((client) => {
            const url = subscriptionUrlForClient(baseUrl, client);
            const showQr = qrId === client.id;
            return (
              <li
                className={
                  showQr
                    ? "subscription-client-row expanded"
                    : "subscription-client-row"
                }
                key={client.id}
              >
                <div className="subscription-client-main">
                  <span
                    aria-hidden="true"
                    className="subscription-client-badge"
                    style={{ background: client.accent }}
                  >
                    {client.monogram}
                  </span>
                  <div className="subscription-client-identity">
                    <strong>{client.name[language]}</strong>
                    <span>{client.detail[language]}</span>
                  </div>
                  <div className="subscription-client-actions">
                    <button
                      className="secondary-button"
                      onClick={() => void copyFor(client)}
                      type="button"
                    >
                      {copiedId === client.id ? labels.copied : labels.copyLink}
                    </button>
                    <button
                      aria-expanded={showQr}
                      className="secondary-button"
                      onClick={() => setQrId(showQr ? null : client.id)}
                      type="button"
                    >
                      {showQr ? labels.hideQr : labels.showQr}
                    </button>
                  </div>
                </div>
                {showQr && (
                  <div className="subscription-client-qr">
                    <QrCode
                      label={`${client.name[language]} ${labels.showQr}`}
                      size={168}
                      value={url}
                    />
                    <p>{labels.qrHint}</p>
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      </section>
    </div>
  );
}
