import type { PropsWithChildren } from "react";

import { useAuthStore } from "../store/auth";

export function AdminRoute({ children }: PropsWithChildren) {
  const viewer = useAuthStore((state) => state.viewer);
  if (!viewer?.roles.includes("ADMIN")) {
    window.location.replace("/security/sessions");
    return null;
  }
  return children;
}
