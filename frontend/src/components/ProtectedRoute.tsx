import { useEffect, type PropsWithChildren } from "react";

import { useAuthStore } from "../store/auth";
import { navigate } from "../lib/navigation";

export function ProtectedRoute({ children }: PropsWithChildren) {
  const accessToken = useAuthStore((state) => state.accessToken);
  const isUser = useAuthStore((state) => state.viewer?.roles.includes("USER") ?? false);
  useEffect(() => {
    if (!accessToken || !isUser) {
      const returnTo = encodeURIComponent(window.location.pathname);
      navigate(`/login?returnTo=${returnTo}`, true);
    }
  }, [accessToken, isUser]);
  if (!accessToken || !isUser) {
    return null;
  }
  return children;
}
