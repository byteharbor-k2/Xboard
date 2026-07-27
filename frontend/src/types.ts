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

export type ProblemDetails = {
  code?: string;
  detail?: string;
  status?: number;
};
