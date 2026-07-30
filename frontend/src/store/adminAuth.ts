import { create } from "zustand";

import type { SessionGrant, Viewer } from "../types";

type AdminAuthState = {
  accessToken: string | null;
  viewer: Viewer | null;
  bootstrapped: boolean;
  setSession: (session: SessionGrant) => void;
  clearSession: () => void;
  finishBootstrap: () => void;
};

export const useAdminAuthStore = create<AdminAuthState>((set) => ({
  accessToken: null,
  viewer: null,
  bootstrapped: false,
  setSession: (session) =>
    set({
      accessToken: session.accessToken,
      viewer: session.viewer,
      bootstrapped: true
    }),
  clearSession: () =>
    set({ accessToken: null, viewer: null, bootstrapped: true }),
  finishBootstrap: () => set({ bootstrapped: true })
}));
