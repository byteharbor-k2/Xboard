import { AppLink } from "./AppLink";

type FreedomBrandProps = {
  href?: string;
  compact?: boolean;
};

export function FreedomBrand({
  href = "/",
  compact = false
}: FreedomBrandProps) {
  return (
    <AppLink className="freedom-brand" href={href} aria-label="SinX Cloud">
      <span className="freedom-brand-mark" aria-hidden="true">
        <svg viewBox="0 0 48 48" role="img">
          <path
            className="freedom-sine"
            d="M3 25c6-17 12 17 19 0s12 17 23 0"
            fill="none"
            stroke="currentColor"
            strokeLinecap="round"
            strokeWidth="3"
          />
          <path
            className="freedom-x"
            d="m31 13 12 12m0-12L31 25"
            fill="none"
            stroke="currentColor"
            strokeLinecap="round"
            strokeWidth="2.5"
          />
        </svg>
      </span>
      {!compact && (
        <span className="freedom-brand-text">
          Sin<span>X</span> <small>Cloud</small>
        </span>
      )}
    </AppLink>
  );
}
