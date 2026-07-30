import { useEffect, useRef } from "react";

type TurnstileApi = {
  render: (
    container: HTMLElement,
    options: {
      sitekey: string;
      callback: (token: string) => void;
      "expired-callback": () => void;
      "error-callback": () => void;
      theme: "dark";
    }
  ) => string;
  reset: (widgetId: string) => void;
  remove: (widgetId: string) => void;
};

declare global {
  interface Window {
    turnstile?: TurnstileApi;
  }
}

type TurnstileWidgetProps = {
  siteKey: string;
  resetCounter: number;
  onToken: (token: string) => void;
};

export function TurnstileWidget({
  siteKey,
  resetCounter,
  onToken
}: TurnstileWidgetProps) {
  const container = useRef<HTMLDivElement>(null);
  const widgetId = useRef<string | null>(null);

  useEffect(() => {
    function renderWidget() {
      if (!container.current || !window.turnstile || widgetId.current) {
        return;
      }
      widgetId.current = window.turnstile.render(container.current, {
        sitekey: siteKey,
        callback: onToken,
        "expired-callback": () => onToken(""),
        "error-callback": () => onToken(""),
        theme: "dark"
      });
    }

    let script = document.querySelector<HTMLScriptElement>(
      'script[data-sinx-turnstile="true"]'
    );
    if (!script) {
      script = document.createElement("script");
      script.src =
        "https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit";
      script.async = true;
      script.defer = true;
      script.dataset.sinxTurnstile = "true";
      document.head.appendChild(script);
    }
    script.addEventListener("load", renderWidget);
    renderWidget();

    return () => {
      script?.removeEventListener("load", renderWidget);
      if (widgetId.current && window.turnstile) {
        window.turnstile.remove(widgetId.current);
      }
      widgetId.current = null;
    };
  }, [onToken, siteKey]);

  useEffect(() => {
    if (widgetId.current && window.turnstile) {
      window.turnstile.reset(widgetId.current);
    }
  }, [resetCounter]);

  return <div className="turnstile-widget" ref={container} />;
}
