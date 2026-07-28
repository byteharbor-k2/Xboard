import { useEffect, type PropsWithChildren } from "react";

import { useAuthStore } from "../store/auth";
import { navigate } from "../lib/navigation";

export function AdminRoute({ children }: PropsWithChildren) {
  const viewer = useAuthStore((state) => state.viewer);
  useEffect(() => {
    if (!viewer?.roles.includes("ADMIN")) {
      navigate("/security/sessions", true);
    }
  }, [viewer]);
  if (!viewer?.roles.includes("ADMIN")) {
    return null;
  }
  return children;
}
