import { useEffect } from "react";

import { ProtectedRoute } from "./components/ProtectedRoute";
import { AdminRoute } from "./components/AdminRoute";
import { refreshSession } from "./lib/http";
import { navigate, usePathname } from "./lib/navigation";
import { DeviceSessionsPage } from "./pages/DeviceSessionsPage";
import { AdminAuditPage } from "./pages/AdminAuditPage";
import { AdminMfaPage } from "./pages/AdminMfaPage";
import { LoginPage } from "./pages/LoginPage";
import { VerifyEmailPage } from "./pages/VerifyEmailPage";
import { useAuthStore } from "./store/auth";

let bootstrapSession: ReturnType<typeof refreshSession> | null = null;

function RedirectToSessions() {
  useEffect(() => {
    navigate("/security/sessions", true);
  }, []);
  return null;
}

export function App() {
  const bootstrapped = useAuthStore((state) => state.bootstrapped);
  const setSession = useAuthStore((state) => state.setSession);
  const finishBootstrap = useAuthStore((state) => state.finishBootstrap);
  const path = usePathname();

  useEffect(() => {
    bootstrapSession ??= refreshSession();
    void bootstrapSession
      .then(setSession)
      .catch(finishBootstrap);
  }, [finishBootstrap, setSession]);

  if (!bootstrapped) {
    return <div className="app-loading">正在建立安全会话…</div>;
  }

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
  if (path === "/admin/audit") {
    return (
      <ProtectedRoute>
        <AdminRoute>
          <AdminAuditPage />
        </AdminRoute>
      </ProtectedRoute>
    );
  }
  if (path === "/admin/mfa") {
    return (
      <ProtectedRoute>
        <AdminRoute>
          <AdminMfaPage />
        </AdminRoute>
      </ProtectedRoute>
    );
  }
  return <RedirectToSessions />;
}
