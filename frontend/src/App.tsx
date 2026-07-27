import { useEffect } from "react";

import { ProtectedRoute } from "./components/ProtectedRoute";
import { refreshSession } from "./lib/http";
import { DeviceSessionsPage } from "./pages/DeviceSessionsPage";
import { LoginPage } from "./pages/LoginPage";
import { VerifyEmailPage } from "./pages/VerifyEmailPage";
import { useAuthStore } from "./store/auth";

export function App() {
  const bootstrapped = useAuthStore((state) => state.bootstrapped);
  const setSession = useAuthStore((state) => state.setSession);
  const finishBootstrap = useAuthStore((state) => state.finishBootstrap);

  useEffect(() => {
    void refreshSession()
      .then(setSession)
      .catch(finishBootstrap);
  }, [finishBootstrap, setSession]);

  if (!bootstrapped) {
    return <div className="app-loading">正在建立安全会话…</div>;
  }

  const path = window.location.pathname;
  if (path === "/login") {
    return <LoginPage />;
  }
  if (path === "/verify-email") {
    return <VerifyEmailPage />;
  }
  if (path === "/security/sessions" || path === "/") {
    return (
      <ProtectedRoute>
        <DeviceSessionsPage />
      </ProtectedRoute>
    );
  }
  window.location.replace("/security/sessions");
  return null;
}
