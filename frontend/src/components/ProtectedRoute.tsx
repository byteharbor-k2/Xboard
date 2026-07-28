import { useEffect, type PropsWithChildren } from "react";

import { useAuthStore } from "../store/auth";
import { navigate } from "../lib/navigation";

export function ProtectedRoute({ children }: PropsWithChildren) {
  const accessToken = useAuthStore((state) => state.accessToken);
  useEffect(() => {
    if (!accessToken) {
      const returnTo = encodeURIComponent(window.location.pathname);
      navigate(`/login?returnTo=${returnTo}`, true);
    }
  }, [accessToken]);
  if (!accessToken) {
    return null;
  }
  return children;
}
