import { useAuthStore } from "../store/auth";

declare const __FREEDOM_HOME_REVISION__: string;

export function HomePage() {
  const authenticated = useAuthStore((state) => Boolean(state.viewer));
  const source = `/theme/Freedom/assets/index.html?v=${__FREEDOM_HOME_REVISION__}${authenticated ? "&account=1" : ""}`;

  return (
    <iframe
      className="freedom-home-frame"
      src={source}
      title="SinX Cloud"
    />
  );
}
