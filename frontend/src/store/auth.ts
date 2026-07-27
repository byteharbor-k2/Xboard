import { create } from "zustand";

import type { SessionGrant, Viewer } from "../types";

type AuthState = {
  accessToken: string | null;
  viewer: Viewer | null;
  bootstrapped: boolean;
  setSession: (session: SessionGrant) => void;
  clearSession: () => void;
  finishBootstrap: () => void;
};

export const useAuthStore = create<AuthState>((set) => ({
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
