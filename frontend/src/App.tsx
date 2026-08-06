import { useEffect } from "react";

import { ProtectedRoute } from "./components/ProtectedRoute";
import { AdminProtectedRoute } from "./components/AdminProtectedRoute";
import { findAdminNavItem } from "./admin/adminNavigation";
import { refreshAdminSession, refreshSession } from "./lib/http";
import { navigate, usePathname } from "./lib/navigation";
import { AdminDashboardPage } from "./pages/AdminDashboardPage";
import { AdminLoginPage } from "./pages/AdminLoginPage";
import { DeviceSessionsPage } from "./pages/DeviceSessionsPage";
import { AdminMfaPage } from "./pages/AdminMfaPage";
import { AdminPlansPage } from "./pages/AdminPlansPage";
import { AdminMachinesPage } from "./pages/AdminMachinesPage";
import { AdminNodesPage } from "./pages/AdminNodesPage";
import { AdminNodeGroupsPage } from "./pages/AdminNodeGroupsPage";
import { AdminNodeRoutesPage } from "./pages/AdminNodeRoutesPage";
import { AdminModulePlaceholderPage } from "./pages/AdminModulePlaceholderPage";
import { SystemSettingsPage } from "./pages/SystemSettingsPage";
import { LoginPage } from "./pages/LoginPage";
import { HomePage } from "./pages/HomePage";
import { RegisterPage } from "./pages/RegisterPage";
import { ForgotPasswordPage } from "./pages/ForgotPasswordPage";
import { ResetPasswordPage } from "./pages/ResetPasswordPage";
import { TicketsPage } from "./pages/TicketsPage";
import { TrafficDetailsPage } from "./pages/TrafficDetailsPage";
import { AccountOverviewPage } from "./pages/AccountOverviewPage";
import { AccountProfilePage } from "./pages/AccountProfilePage";
import { InvitationsPage } from "./pages/InvitationsPage";
import { KnowledgeBasePage } from "./pages/KnowledgeBasePage";
import { OrdersPage } from "./pages/OrdersPage";
import { PlansPage } from "./pages/PlansPage";
import { useAuthStore } from "./store/auth";
import { useAdminAuthStore } from "./store/adminAuth";

let bootstrapUserSession: ReturnType<typeof refreshSession> | null = null;
let bootstrapAdminSession: ReturnType<typeof refreshAdminSession> | null = null;

function RedirectHome() {
  useEffect(() => {
    navigate("/", true);
  }, []);
  return null;
}

function RedirectDashboard() {
  useEffect(() => {
    navigate("/dashboard", true);
  }, []);
  return null;
}

export function App() {
  const bootstrapped = useAuthStore((state) => state.bootstrapped);
  const setSession = useAuthStore((state) => state.setSession);
  const finishBootstrap = useAuthStore((state) => state.finishBootstrap);
  const adminBootstrapped = useAdminAuthStore(
    (state) => state.bootstrapped
  );
  const setAdminSession = useAdminAuthStore((state) => state.setSession);
  const finishAdminBootstrap = useAdminAuthStore(
    (state) => state.finishBootstrap
  );
  const path = usePathname();
  const adminPath = path.startsWith("/admin");

  useEffect(() => {
    if (adminPath) {
      bootstrapAdminSession ??= refreshAdminSession();
      void bootstrapAdminSession
        .then(setAdminSession)
        .catch(finishAdminBootstrap);
      return;
    }
    bootstrapUserSession ??= refreshSession();
    void bootstrapUserSession
      .then(setSession)
      .catch(finishBootstrap);
  }, [
    adminPath,
    finishAdminBootstrap,
    finishBootstrap,
    setAdminSession,
    setSession
  ]);

  if (adminPath ? !adminBootstrapped : !bootstrapped) {
    return <div className="app-loading">正在建立安全会话…</div>;
  }

  if (path === "/admin/login") {
    return <AdminLoginPage />;
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
  if (path === "/") {
    return <HomePage />;
  }
  if (path === "/account") {
    return <RedirectDashboard />;
  }
  if (path === "/dashboard") {
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
  if (path === "/docs") {
    return (
      <ProtectedRoute>
        <KnowledgeBasePage />
      </ProtectedRoute>
    );
  }
  if (path === "/account/orders") {
    return (
      <ProtectedRoute>
        <OrdersPage />
      </ProtectedRoute>
    );
  }
  if (path === "/account/invitations") {
    return (
      <ProtectedRoute>
        <InvitationsPage />
      </ProtectedRoute>
    );
  }
  if (path === "/account/tickets") {
    return (
      <ProtectedRoute>
        <TicketsPage />
      </ProtectedRoute>
    );
  }
  if (path === "/account/traffic") {
    return (
      <ProtectedRoute>
        <TrafficDetailsPage />
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
      <AdminProtectedRoute>
        <AdminDashboardPage />
      </AdminProtectedRoute>
    );
  }
  if (path === "/admin/mfa") {
    return (
      <AdminProtectedRoute>
        <AdminMfaPage />
      </AdminProtectedRoute>
    );
  }
  if (
    path.startsWith("/admin/system/settings") ||
    path === "/admin/nodes/settings" ||
    path === "/admin/nodes/subscription-templates"
  ) {
    return (
      <AdminProtectedRoute>
        <SystemSettingsPage />
      </AdminProtectedRoute>
    );
  }
  if (path === "/admin/finance/plans") {
    return (
      <AdminProtectedRoute>
        <AdminPlansPage />
      </AdminProtectedRoute>
    );
  }
  if (path === "/admin/nodes/machines") {
    return (
      <AdminProtectedRoute>
        <AdminMachinesPage />
      </AdminProtectedRoute>
    );
  }
  if (path === "/admin/nodes") {
    return (
      <AdminProtectedRoute>
        <AdminNodesPage />
      </AdminProtectedRoute>
    );
  }
  if (path === "/admin/nodes/groups") {
    return (
      <AdminProtectedRoute>
        <AdminNodeGroupsPage />
      </AdminProtectedRoute>
    );
  }
  if (path === "/admin/nodes/routes") {
    return (
      <AdminProtectedRoute>
        <AdminNodeRoutesPage />
      </AdminProtectedRoute>
    );
  }
  const adminItem = findAdminNavItem(path);
  if (adminItem) {
    return (
      <AdminProtectedRoute>
        <AdminModulePlaceholderPage item={adminItem} />
      </AdminProtectedRoute>
    );
  }
  return <RedirectHome />;
}
