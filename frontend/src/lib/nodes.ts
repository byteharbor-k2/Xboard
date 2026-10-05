import { graphQl } from "./http";

export type ViewerNodeStatus = "ONLINE" | "OFFLINE" | "UNKNOWN";

export type ViewerNode = {
  id: string;
  name: string;
  protocol: string;
  trafficRate: string;
  onlineStatus: ViewerNodeStatus;
  lastSeenAt: string | null;
  tags: string[];
};

export function fetchViewerNodes(accessToken: string) {
  return graphQl<{ viewerNodes: ViewerNode[] }>(
    accessToken,
    `query ViewerNodes {
      viewerNodes {
        id
        name
        protocol
        trafficRate
        onlineStatus
        lastSeenAt
        tags
      }
    }`
  ).then((result) => result.viewerNodes);
}
