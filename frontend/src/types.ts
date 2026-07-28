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

export type DeviceSession = {
  id: string;
  deviceLabel: string;
  createdAt: string;
  lastUsedAt: string;
  expiresAt: string;
  current: boolean;
};

export type AdminAuditLog = {
  id: string;
  actorId: string;
  actorEmail: string;
  action: string;
  httpMethod: string;
  requestPath: string;
  responseStatus: number;
  outcome: "SUCCESS" | "FAILURE";
  durationMs: string;
  requestId?: string;
  ipAddress?: string;
  userAgent?: string;
  occurredAt: string;
};

export type ProblemDetails = {
  code?: string;
  detail?: string;
  status?: number;
};
