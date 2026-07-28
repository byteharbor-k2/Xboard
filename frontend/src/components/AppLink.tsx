import type { AnchorHTMLAttributes, MouseEvent } from "react";

import { navigate } from "../lib/navigation";

type AppLinkProps = AnchorHTMLAttributes<HTMLAnchorElement> & {
  href: string;
};

export function AppLink({
  href,
  onClick,
  ...attributes
}: AppLinkProps) {
  function handleClick(event: MouseEvent<HTMLAnchorElement>) {
    onClick?.(event);
    if (
      event.defaultPrevented
      || event.button !== 0
      || event.metaKey
      || event.ctrlKey
      || event.shiftKey
      || event.altKey
    ) {
      return;
    }
    event.preventDefault();
    navigate(href);
  }

  return <a {...attributes} href={href} onClick={handleClick} />;
}
