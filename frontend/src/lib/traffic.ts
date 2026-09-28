import { graphQl } from "./http";

/**
 * One row of the account's daily traffic ledger as the user gateway returns
 * it: a node-day slice with both the raw bytes the node carried and what the
 * billing actually charged.
 */
export type ViewerTrafficDaily = {
  day: string;
  nodeName: string | null;
  uploadBytes: string;
  downloadBytes: string;
  billedBytes: string;
};

/**
 * The account's own traffic history, kept for the trailing month. Rows appear
 * only where the node report charged the account, so the billed column
 * reconciles with the subscription counters.
 */
export async function fetchViewerTrafficDaily(
  accessToken: string
): Promise<ViewerTrafficDaily[]> {
  const data = await graphQl<{ viewerTrafficDaily: ViewerTrafficDaily[] }>(
    accessToken,
    `query ViewerTrafficDaily {
       viewerTrafficDaily {
         day
         nodeName
         uploadBytes
         downloadBytes
         billedBytes
       }
     }`
  );
  return data.viewerTrafficDaily;
}
