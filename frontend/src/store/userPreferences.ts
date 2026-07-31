import { create } from "zustand";

export type UserLanguage = "zh-CN" | "en-US";

type UserPreferences = {
  language: UserLanguage;
  setLanguage: (language: UserLanguage) => void;
};

function getInitialLanguage(): UserLanguage {
  return window.localStorage.getItem("sinx-user-language") === "en-US"
    ? "en-US"
    : "zh-CN";
}

const initialLanguage = getInitialLanguage();
document.documentElement.lang = initialLanguage;

export const useUserPreferences = create<UserPreferences>((set) => ({
  language: initialLanguage,
  setLanguage: (language) => {
    window.localStorage.setItem("sinx-user-language", language);
    document.documentElement.lang = language;
    set({ language });
  }
}));
