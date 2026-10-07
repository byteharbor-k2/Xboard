import { graphQl } from "./http";

export type ViewerBalanceSummary = {
  balanceMinor: string;
  openingBalanceMinor: string;
  totalCreditsMinor: string;
  totalDebitsMinor: string;
  recordedSince: string | null;
};

export type ViewerBalanceLog = {
  id: string;
  type:
    | "OPENING_BALANCE"
    | "ORDER_PAYMENT"
    | "ORDER_REFUND"
    | "SURPLUS_CREDIT"
    | "COMMISSION_CREDIT";
  amountMinor: string;
  balanceAfterMinor: string;
  currency: string;
  tradeNo: string | null;
  canViewOrder: boolean;
  createdAt: string;
};

export type ViewerBalanceLogPage = {
  items: ViewerBalanceLog[];
  totalCount: number;
  page: number;
  limit: number;
};

export type ViewerBalanceData = {
  summary: ViewerBalanceSummary;
  logs: ViewerBalanceLogPage;
};

/** Fetches the current cash-balance reconciliation and one server-paginated ledger page. */
export async function fetchViewerBalanceData(
  accessToken: string,
  page: number,
  limit = 20
): Promise<ViewerBalanceData> {
  const data = await graphQl<{
    viewerBalanceSummary: ViewerBalanceSummary;
    viewerBalanceLogs: ViewerBalanceLogPage;
  }>(
    accessToken,
    `query ViewerBalance($page: Int!, $limit: Int!) {
       viewerBalanceSummary {
         balanceMinor openingBalanceMinor totalCreditsMinor totalDebitsMinor recordedSince
       }
       viewerBalanceLogs(page: $page, limit: $limit) {
          items { id type amountMinor balanceAfterMinor currency tradeNo canViewOrder createdAt }
         totalCount page limit
       }
     }`,
    { page, limit }
  );
  return { summary: data.viewerBalanceSummary, logs: data.viewerBalanceLogs };
}
