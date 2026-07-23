// API shapes. These mirror the Auth and Profile service contracts exactly —
// keep them in sync with services/auth and services/profile. TypeScript here is
// the robustness lever the whole stack was chosen for: a drift between these
// and the backend surfaces as a compile error, not a runtime surprise.

export interface Tokens {
  accessToken: string;
  idToken: string;
  refreshToken: string;
}

export interface RegisterResult {
  userId: string;
  email: string;
}

// POST /auth/login. Either MFA is required (tokens null, session present) or
// it isn't (tokens present). Modelled as a union so callers must handle both.
export interface LoginResult {
  mfaRequired: boolean;
  mfaSession: string | null;
  accessToken: string | null;
  idToken: string | null;
  refreshToken: string | null;
}

// POST /auth/mfa/verify. Post-enrollment returns just { verified }; the login
// challenge branch also returns tokens.
export interface MfaVerifyResult {
  verified: boolean;
  accessToken?: string;
  idToken?: string;
  refreshToken?: string;
}

export interface MfaEnrollResult {
  secretCode: string;
  otpauthUrl: string;
}

export interface RefreshResult {
  accessToken: string;
  idToken: string;
}

export interface Profile {
  userId: string;
  displayName: string;
  avatarUrl: string | null;
  bio: string | null;
  createdAt: string;
  updatedAt: string;
}

// PATCH /profiles/:id — every field optional; null clears an optional field.
export interface ProfileUpdate {
  displayName?: string;
  avatarUrl?: string | null;
  bio?: string | null;
}

export interface ApiError {
  error: string;
}
