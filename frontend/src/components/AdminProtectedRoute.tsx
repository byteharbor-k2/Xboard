import { useEffect, type PropsWithChildren } from "react";

import { navigate } from "../lib/navigation";
import { useAdminAuthStore } from "../store/adminAuth";

export function AdminProtectedRoute({ children }: PropsWithChildren) {
  const accessToken = useAdminAuthStore((state) => state.accessToken);
  const viewer = useAdminAuthStore((state) => state.viewer);

  useEffect(() => {
    if (!accessToken || !viewer?.roles.includes("ADMIN")) {
      navigate("/admin/login", true);
    }
  }, [accessToken, viewer]);

  if (!accessToken || !viewer?.roles.includes("ADMIN")) {
    return null;
  }
  return children;
}
