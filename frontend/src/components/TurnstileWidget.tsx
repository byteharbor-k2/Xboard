import { useEffect, useRef } from "react";

type TurnstileApi = {
  render: (
    container: HTMLElement,
    options: {
      sitekey: string;
      callback: (token: string) => void;
      "expired-callback": () => void;
      "error-callback": () => boolean;
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

function trackTurnstileScriptState(script: HTMLScriptElement) {
  if (script.dataset.sinxTurnstileStateTracking === "true") return;

  // These shared-script listeners only persist load state. Unlike widget
  // callbacks, they must outlive an individual component to catch late errors.
  script.dataset.sinxTurnstileStateTracking = "true";
  script.addEventListener("load", () => {
    script.dataset.sinxTurnstileLoaded = "true";
  });
  script.addEventListener("error", () => {
    script.dataset.sinxTurnstileFailed = "true";
  });
}

type TurnstileWidgetProps = {
  siteKey: string;
  resetCounter: number;
  onToken: (token: string) => void;
  onError: () => void;
};

export function TurnstileWidget({
  siteKey,
  resetCounter,
  onToken,
  onError
}: TurnstileWidgetProps) {
  const container = useRef<HTMLDivElement>(null);
  const widgetId = useRef<string | null>(null);

  useEffect(() => {
    function renderWidget() {
      if (!container.current || !window.turnstile || widgetId.current) {
        return;
      }
      try {
        widgetId.current = window.turnstile.render(container.current, {
          sitekey: siteKey,
          callback: (token) => {
            onToken(token);
          },
          "expired-callback": () => {
            onToken("");
            onError();
          },
          "error-callback": () => {
            onToken("");
            onError();
            return false;
          },
          theme: "dark"
        });
      } catch {
        onError();
      }
    }

    let script = document.querySelector<HTMLScriptElement>(
      'script[data-sinx-turnstile="true"]'
    );
    if (script?.dataset.sinxTurnstileFailed === "true") {
      script.remove();
      script = null;
    }
    let appendScript = false;
    if (!script) {
      script = document.createElement("script");
      script.src =
        "https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit";
      script.async = true;
      script.defer = true;
      script.dataset.sinxTurnstile = "true";
      appendScript = true;
    }
    trackTurnstileScriptState(script);
    const scriptError = () => {
      onError();
    };
    const scriptLoad = () => {
      renderWidget();
      if (!window.turnstile) onError();
    };
    script.addEventListener("load", scriptLoad);
    script.addEventListener("error", scriptError);
    if (appendScript) document.head.appendChild(script);
    renderWidget();
    if (script.dataset.sinxTurnstileLoaded === "true" && !window.turnstile) {
      scriptError();
    }

    return () => {
      script?.removeEventListener("load", scriptLoad);
      script?.removeEventListener("error", scriptError);
      if (widgetId.current && window.turnstile) {
        window.turnstile.remove(widgetId.current);
      }
      widgetId.current = null;
    };
  }, [onError, onToken, siteKey]);

  useEffect(() => {
    if (widgetId.current && window.turnstile) {
      window.turnstile.reset(widgetId.current);
    }
  }, [resetCounter]);

  return <div className="turnstile-widget" ref={container} />;
}
