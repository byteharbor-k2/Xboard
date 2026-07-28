import { useEffect, type PropsWithChildren } from "react";

import { useAuthStore } from "../store/auth";

export function AdminRoute({ children }: PropsWithChildren) {
  const viewer = useAuthStore((state) => state.viewer);
  useEffect(() => {
    if (!viewer?.roles.includes("ADMIN")) {
      window.location.replace("/security/sessions");
    }
  }, [viewer]);
  if (!viewer?.roles.includes("ADMIN")) {
    return null;
  }
  return children;
}
