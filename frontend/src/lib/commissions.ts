import { graphQl } from "./http";

export type ViewerCommissionSummary = {
  effectiveRatePercent: number;
  commissionType: number;
  firstPaymentOnly: boolean;
  pendingMinor: string;
  confirmedPendingMinor: string;
  earnedMinor: string;
  autoConfirmEnabled: boolean;
  distributionEnabled: boolean;
  distributionL1: number;
  distributionL2: number;
  distributionL3: number;
  payoutDestination: string;
};

export type ViewerCommissionLog = {
  id: string;
  tradeNo: string;
  orderAmountMinor: string;
  commissionBaseMinor: string;
  amountMinor: string;
  level: number;
  createdAt: string;
};

export type ViewerCommissionLogPage = {
  items: ViewerCommissionLog[];
  totalCount: number;
  page: number;
  limit: number;
};

export type ViewerCommissionData = {
  balanceMinor: string;
  summary: ViewerCommissionSummary;
  logs: ViewerCommissionLogPage;
};

/** Fetches commission totals, current site balance, and one actual payout-log page. */
export async function fetchViewerCommissionData(
  accessToken: string,
  page: number,
  limit = 20
): Promise<ViewerCommissionData> {
  const data = await graphQl<{
    viewer: { balanceMinor: string };
    viewerCommissionSummary: ViewerCommissionSummary;
    viewerCommissionLogs: ViewerCommissionLogPage;
  }>(
    accessToken,
    `query ViewerCommission($page: Int!, $limit: Int!) {
       viewer { balanceMinor }
       viewerCommissionSummary {
         effectiveRatePercent commissionType firstPaymentOnly
         pendingMinor confirmedPendingMinor earnedMinor
         autoConfirmEnabled distributionEnabled
         distributionL1 distributionL2 distributionL3 payoutDestination
       }
       viewerCommissionLogs(page: $page, limit: $limit) {
         items { id tradeNo orderAmountMinor commissionBaseMinor amountMinor level createdAt }
         totalCount page limit
       }
     }`,
    { page, limit }
  );
  return {
    balanceMinor: data.viewer.balanceMinor,
    summary: data.viewerCommissionSummary,
    logs: data.viewerCommissionLogs
  };
}
