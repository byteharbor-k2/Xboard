export type NetworkMapNode = {
  id: string;
  label: string;
  latitude: number;
  longitude: number;
  state: "AVAILABLE" | "DEGRADED" | "MAINTENANCE";
};

type NetworkGlobeProps = {
  nodes: NetworkMapNode[];
  selectedNodeId: string | null;
  onSelect: (node: NetworkMapNode) => void;
};

function project(node: NetworkMapNode) {
  return {
    x: 32 + ((node.longitude + 180) / 360) * 336,
    y: 48 + ((90 - node.latitude) / 180) * 304
  };
}

export function NetworkGlobe({
  nodes,
  selectedNodeId,
  onSelect
}: NetworkGlobeProps) {
  return (
    <div className="network-globe" aria-label="Global proxy node map">
      <div className="network-globe-aura" />
      <svg
        className="network-globe-map"
        role="img"
        viewBox="0 0 400 400"
      >
        <defs>
          <radialGradient id="globeSurface" cx="36%" cy="28%" r="70%">
            <stop offset="0%" stopColor="#16385f" />
            <stop offset="62%" stopColor="#0b213e" />
            <stop offset="100%" stopColor="#06152b" />
          </radialGradient>
          <linearGradient id="landSurface" x1="0" x2="1" y1="0" y2="1">
            <stop offset="0%" stopColor="#1c8aa2" />
            <stop offset="100%" stopColor="#126477" />
          </linearGradient>
          <clipPath id="globeClip">
            <circle cx="200" cy="200" r="168" />
          </clipPath>
          <filter id="nodeGlow">
            <feGaussianBlur result="blur" stdDeviation="4" />
            <feMerge>
              <feMergeNode in="blur" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
        </defs>
        <circle
          className="network-globe-sphere"
          cx="200"
          cy="200"
          fill="url(#globeSurface)"
          r="168"
        />
        <g className="network-globe-grid" clipPath="url(#globeClip)">
          <ellipse cx="200" cy="200" rx="74" ry="168" />
          <ellipse cx="200" cy="200" rx="132" ry="168" />
          <path d="M32 200h336" />
          <ellipse cx="200" cy="200" rx="168" ry="69" />
          <ellipse cx="200" cy="200" rx="168" ry="125" />
        </g>
        <g
          className="network-globe-land"
          clipPath="url(#globeClip)"
          fill="url(#landSurface)"
        >
          <path d="M51 134 72 98l34-22 39 3 20 18-10 18-24 5-12 17-28 9-13 27-21-5-13-19Z" />
          <path d="m126 165 20 12 11 30-8 29-15 26-10 39-18-12 4-34-12-29 6-39Z" />
          <path d="m180 111 19-13 21 8 10 17-15 10-21-2-17-9Z" />
          <path d="m197 147 25-3 25 17 2 37-15 47-21 30-15-31-13-39 5-34Z" />
          <path d="m221 111 43-23 50 8 34 27-9 20-40 5-18 20-31-3-17-19-26-12Z" />
          <path d="m287 245 37-14 28 19-6 29-35 12-29-22Z" />
          <path d="m355 187 13-4 11 12-7 16-15-7Z" />
        </g>
        <g className="network-node-layer" clipPath="url(#globeClip)">
          {nodes.map((node) => {
            const point = project(node);
            const selected = selectedNodeId === node.id;
            return (
              <g
                className={`network-node-marker ${node.state.toLowerCase()}${selected ? " selected" : ""}`}
                key={node.id}
                onClick={() => onSelect(node)}
                role="button"
                tabIndex={0}
                transform={`translate(${point.x} ${point.y})`}
                onKeyDown={(event) => {
                  if (event.key === "Enter" || event.key === " ") {
                    event.preventDefault();
                    onSelect(node);
                  }
                }}
              >
                <circle className="network-node-pulse" r="12" />
                <circle className="network-node-core" r="4.5" />
              </g>
            );
          })}
        </g>
        <circle
          className="network-globe-rim"
          cx="200"
          cy="200"
          fill="none"
          r="168"
        />
      </svg>
    </div>
  );
}
