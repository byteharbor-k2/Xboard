import { useAuthStore } from "../store/auth";

export function HomePage() {
  const authenticated = useAuthStore((state) => Boolean(state.viewer));
  const source = authenticated
    ? "/theme/Freedom/assets/index.html?account=1"
    : "/theme/Freedom/assets/index.html";

  return (
    <iframe
      className="freedom-home-frame"
      src={source}
      title="SinX Cloud"
    />
  );
}
