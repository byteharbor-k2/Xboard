import { useEffect, type PropsWithChildren } from "react";

import { useAuthStore } from "../store/auth";

export function ProtectedRoute({ children }: PropsWithChildren) {
  const accessToken = useAuthStore((state) => state.accessToken);
  useEffect(() => {
    if (!accessToken) {
      const returnTo = encodeURIComponent(window.location.pathname);
      window.location.replace(`/login?returnTo=${returnTo}`);
    }
  }, [accessToken]);
  if (!accessToken) {
    return null;
  }
  return children;
}
