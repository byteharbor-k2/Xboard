import { useEffect } from "react";

import { ProtectedRoute } from "./components/ProtectedRoute";
import { AdminRoute } from "./components/AdminRoute";
import { findAdminNavItem } from "./admin/adminNavigation";
import { refreshSession } from "./lib/http";
import { navigate, usePathname } from "./lib/navigation";
import { AdminDashboardPage } from "./pages/AdminDashboardPage";
import { DeviceSessionsPage } from "./pages/DeviceSessionsPage";
import { AdminMfaPage } from "./pages/AdminMfaPage";
import { AdminModulePlaceholderPage } from "./pages/AdminModulePlaceholderPage";
import { SystemSettingsPage } from "./pages/SystemSettingsPage";
import { LoginPage } from "./pages/LoginPage";
import { HomePage } from "./pages/HomePage";
import { RegisterPage } from "./pages/RegisterPage";
import { ForgotPasswordPage } from "./pages/ForgotPasswordPage";
import { ResetPasswordPage } from "./pages/ResetPasswordPage";
import { AccountOverviewPage } from "./pages/AccountOverviewPage";
import { AccountProfilePage } from "./pages/AccountProfilePage";
import { PlansPage } from "./pages/PlansPage";
import { VerifyEmailPage } from "./pages/VerifyEmailPage";
import { useAuthStore } from "./store/auth";

let bootstrapSession: ReturnType<typeof refreshSession> | null = null;

function RedirectHome() {
  useEffect(() => {
    navigate("/", true);
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
  if (path === "/register") {
    return <RegisterPage />;
  }
  if (path === "/forgot-password") {
    return <ForgotPasswordPage />;
  }
  if (path === "/reset-password") {
    return <ResetPasswordPage />;
  }
  if (path === "/verify-email") {
    return <VerifyEmailPage />;
  }
  if (path === "/") {
    return <HomePage />;
  }
  if (path === "/account") {
    return (
      <ProtectedRoute>
        <AccountOverviewPage />
      </ProtectedRoute>
    );
  }
  if (path === "/account/profile") {
    return (
      <ProtectedRoute>
        <AccountProfilePage />
      </ProtectedRoute>
    );
  }
  if (path === "/plans") {
    return (
      <ProtectedRoute>
        <PlansPage />
      </ProtectedRoute>
    );
  }
  if (path === "/security/sessions") {
    return (
      <ProtectedRoute>
        <DeviceSessionsPage />
      </ProtectedRoute>
    );
  }
  if (path === "/admin" || path === "/admin/dashboard") {
    return (
      <ProtectedRoute>
        <AdminRoute>
          <AdminDashboardPage />
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
  if (path.startsWith("/admin/system/settings")) {
    return (
      <ProtectedRoute>
        <AdminRoute>
          <SystemSettingsPage />
        </AdminRoute>
      </ProtectedRoute>
    );
  }
  const adminItem = findAdminNavItem(path);
  if (adminItem) {
    return (
      <ProtectedRoute>
        <AdminRoute>
          <AdminModulePlaceholderPage item={adminItem} />
        </AdminRoute>
      </ProtectedRoute>
    );
  }
  return <RedirectHome />;
}
