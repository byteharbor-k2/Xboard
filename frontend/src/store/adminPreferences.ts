import { create } from "zustand";

import type { AdminLanguage } from "../admin/adminNavigation";

type AdminPreferences = {
  language: AdminLanguage;
  setLanguage: (language: AdminLanguage) => void;
};

function getInitialLanguage(): AdminLanguage {
  return window.localStorage.getItem("sinx-admin-language") === "en-US"
    ? "en-US"
    : "zh-CN";
}

export const useAdminPreferences = create<AdminPreferences>((set) => ({
  language: getInitialLanguage(),
  setLanguage: (language) => {
    window.localStorage.setItem("sinx-admin-language", language);
    set({ language });
  }
}));
