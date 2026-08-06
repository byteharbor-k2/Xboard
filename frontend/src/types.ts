export type Viewer = {
  id: string;
  email: string;
  displayName: string;
  emailVerified: boolean;
  roles: string[];
  createdAt: string;
};

export type SessionGrant = {
  accessToken: string;
  accessTokenExpiresAt: string;
  sessionId: string;
  viewer: Viewer;
};

export type LoginResult = SessionGrant;

export type MfaEnrollmentRequired = {
  mfaEnrollmentRequired: true;
  mfaRequired: false;
  enrollmentToken: string;
  expiresAt: string;
};

export type AdminMfaRequired = {
  mfaRequired: true;
  mfaEnrollmentRequired: false;
  challengeToken: string;
  expiresAt: string;
};

export type AdminLoginResult =
  | AdminMfaRequired
  | MfaEnrollmentRequired;

export type MfaStatus = {
  enabled: boolean;
  enabledAt: string | null;
};

export type MfaEnrollment = {
  secret: string;
  otpauthUri: string;
};

export type MfaEnrollmentComplete = {
  recoveryCodes: string[];
};

export type DeviceSession = {
  id: string;
  deviceLabel: string;
  createdAt: string;
  lastUsedAt: string;
  expiresAt: string;
  current: boolean;
};

export type ProblemDetails = {
  code?: string;
  detail?: string;
  status?: number;
};

export type RegistrationConfig = {
  emailVerificationRequired: boolean;
  turnstileEnabled: boolean;
  turnstileSiteKey: string | null;
  termsUrl: string | null;
  emailDomainAllowlistEnabled: boolean;
  allowedEmailDomains: string[];
  invitationRequired: boolean;
};

export type BillingPeriod =
  | "MONTHLY"
  | "QUARTERLY"
  | "HALF_YEARLY"
  | "YEARLY"
  | "TWO_YEARLY"
  | "THREE_YEARLY"
  | "ONETIME"
  | "RESET_TRAFFIC";

export type PlanType = "SUBSCRIPTION" | "TRAFFIC_PACKAGE";

export type TrafficResetPolicy =
  | "FIRST_DAY_OF_MONTH"
  | "MONTHLY_FROM_ACTIVATION"
  | "NEVER"
  | "FIRST_DAY_OF_YEAR"
  | "YEARLY_FROM_ACTIVATION";

export type PlanPrice = {
  period: BillingPeriod;
  amountMinor: string;
  currency: string;
  durationDays: number | null;
  monthCount: number | null;
};

export type PlanOffer = {
  id: string;
  name: string;
  description: string;
  tags: string[];
  planType: PlanType;
  transferLimitBytes: string;
  speedLimitMbps: number | null;
  deviceLimit: number | null;
  resetPolicy: TrafficResetPolicy;
  renewable: boolean;
  resettable: boolean;
  purchaseLimitPerUser: number | null;
  capacityRemaining: number | null;
  prices: PlanPrice[];
};

export type ManagedPlanPrice = {
  period: BillingPeriod;
  amountMinor: number;
  currency: string;
};

export type ManagedPlan = {
  id: string;
  name: string;
  description: string;
  tags: string[];
  planType: PlanType;
  transferLimitBytes: string;
  speedLimitMbps: number | null;
  deviceLimit: number | null;
  resetPolicy: TrafficResetPolicy;
  capacityLimit: number | null;
  resettable: boolean;
  purchaseLimitPerUser: number | null;
  serverGroupId: number | null;
  published: boolean;
  sellable: boolean;
  renewable: boolean;
  sortOrder: number;
  subscriberCount: number;
  activeSubscriberCount: number;
  prices: ManagedPlanPrice[];
};

export type PlanDraft = Omit<
  ManagedPlan,
  "id" | "subscriberCount" | "activeSubscriberCount"
>;

export type EntitlementState =
  | "ACTIVE"
  | "EXPIRED"
  | "EXHAUSTED"
  | "CANCELED";

export type SubscriptionEntitlement = {
  id: string;
  planId: string;
  planName: string;
  state: EntitlementState;
  transferLimitBytes: string;
  uploadedBytes: string;
  downloadedBytes: string;
  usedBytes: string;
  remainingBytes: string;
  usagePercent: number;
  speedLimitMbps: number | null;
  deviceLimit: number | null;
  resetPolicy: TrafficResetPolicy;
  startsAt: string;
  expiresAt: string | null;
  nextResetAt: string | null;
};
