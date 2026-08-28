import { useMemo } from "react";

import { encodeQrCode, qrCodeSvgPath } from "../lib/qrcode";

type QrCodeProps = {
  value: string;
  label: string;
  /** Rendered edge length in CSS pixels. */
  size?: number;
};

/**
 * Renders a QR Code as inline SVG. Returns null when the payload cannot be
 * encoded, so callers keep showing their manual-entry fallback.
 */
export function QrCode({ value, label, size = 200 }: QrCodeProps) {
  const svg = useMemo(() => {
    try {
      return qrCodeSvgPath(encodeQrCode(value));
    } catch {
      return null;
    }
  }, [value]);

  if (!svg) {
    return null;
  }

  return (
    <svg
      aria-label={label}
      className="qr-code"
      height={size}
      role="img"
      shapeRendering="crispEdges"
      viewBox={`0 0 ${svg.extent} ${svg.extent}`}
      width={size}
      xmlns="http://www.w3.org/2000/svg"
    >
      <rect fill="#ffffff" height={svg.extent} width={svg.extent} x="0" y="0" />
      <path d={svg.path} fill="#000000" />
    </svg>
  );
}
