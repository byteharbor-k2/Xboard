import { graphQl } from "./http";
import type { ViewerBalanceSummary } from "./balance";

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
  balanceSummary: ViewerBalanceSummary;
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
    viewerBalanceSummary: ViewerBalanceSummary;
    viewerCommissionSummary: ViewerCommissionSummary;
    viewerCommissionLogs: ViewerCommissionLogPage;
  }>(
    accessToken,
    `query ViewerCommission($page: Int!, $limit: Int!) {
       viewerBalanceSummary {
         balanceMinor openingBalanceMinor totalCreditsMinor totalDebitsMinor recordedSince
       }
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
    balanceMinor: data.viewerBalanceSummary.balanceMinor,
    balanceSummary: data.viewerBalanceSummary,
    summary: data.viewerCommissionSummary,
    logs: data.viewerCommissionLogs
  };
}
