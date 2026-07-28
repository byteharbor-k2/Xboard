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

export type MfaRequired = {
  mfaRequired: true;
  challengeToken: string;
  challengeExpiresAt: string;
};

export type LoginResult = SessionGrant | MfaRequired;

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
