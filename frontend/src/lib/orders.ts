import { graphQl, publicGraphQl } from "./http";
import type {
  BillingPeriod,
  OrderQuote,
  PlanOffer,
  ServiceOrder
} from "../types";

const PLAN_OFFER_FIELDS = `
  id
  name
  description
  tags
  planType
  transferLimitBytes
  speedLimitMbps
  deviceLimit
  resetPolicy
  renewable
  resettable
  purchaseLimitPerUser
  capacityRemaining
  prices {
    period
    amountMinor
    currency
    durationDays
    monthCount
  }
`;

const QUOTE_FIELDS = `
  planId
  planName
  period
  orderType
  currency
  originalAmount
  discountAmount
  surplusAmount
  surplusCredit
  balanceAmount
  totalAmount
  couponCode
  couponName
  accountBalanceMinor
`;

const ORDER_FIELDS = `
  id
  tradeNo
  planId
  planName
  period
  orderType
  status
  currency
  originalAmount
  discountAmount
  surplusAmount
  surplusCredit
  balanceAmount
  totalAmount
  createdAt
  paidAt
`;

export async function fetchPlanOffer(
  planId: string
): Promise<PlanOffer | null> {
  const data = await publicGraphQl<{ planOffer: PlanOffer | null }>(
    `query PlanOffer($id: ID!) { planOffer(id: $id) { ${PLAN_OFFER_FIELDS} } }`,
    { id: planId }
  );
  return data.planOffer;
}

/**
 * Prices a purchase without touching anything, so it is safe to call on every
 * period or coupon change.
 */
export async function fetchOrderQuote(
  accessToken: string,
  planId: string,
  period: BillingPeriod,
  couponCode?: string
): Promise<OrderQuote> {
  const data = await graphQl<{ orderQuote: OrderQuote }>(
    accessToken,
    `query OrderQuote($planId: ID!, $period: BillingPeriod!, $couponCode: String) {
       orderQuote(planId: $planId, period: $period, couponCode: $couponCode) {
         ${QUOTE_FIELDS}
       }
     }`,
    { planId, period, couponCode: couponCode || null }
  );
  return data.orderQuote;
}

export async function placeOrder(
  accessToken: string,
  planId: string,
  period: BillingPeriod,
  couponCode?: string
): Promise<ServiceOrder> {
  const data = await graphQl<{ placeOrder: ServiceOrder }>(
    accessToken,
    `mutation PlaceOrder($planId: ID!, $period: BillingPeriod!, $couponCode: String) {
       placeOrder(planId: $planId, period: $period, couponCode: $couponCode) {
         ${ORDER_FIELDS}
       }
     }`,
    { planId, period, couponCode: couponCode || null }
  );
  return data.placeOrder;
}

export async function fetchViewerOrders(
  accessToken: string
): Promise<ServiceOrder[]> {
  const data = await graphQl<{ viewerOrders: ServiceOrder[] }>(
    accessToken,
    `query ViewerOrders { viewerOrders { ${ORDER_FIELDS} } }`
  );
  return data.viewerOrders;
}

export async function cancelOrder(
  accessToken: string,
  tradeNo: string
): Promise<void> {
  await graphQl<{ cancelOrder: boolean }>(
    accessToken,
    `mutation CancelOrder($tradeNo: String!) { cancelOrder(tradeNo: $tradeNo) }`,
    { tradeNo }
  );
}
