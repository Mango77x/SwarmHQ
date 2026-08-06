// Every api/*.ts module calls this instead of the global fetch, so the
// operator's Keycloak access token (set once, from App.tsx, the moment
// react-oidc-context has one) rides along on every REST call without each
// module needing its own auth plumbing. GET requests still succeed with no
// token at all - the backend's SecurityConfig leaves reads public - this
// only matters for the POST/PUT calls an unauthenticated or OBSERVER-only
// visitor would otherwise get a 401/403 from.
let accessToken: string | null = null;

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export function apiFetch(input: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  if (accessToken) {
    headers.set("Authorization", `Bearer ${accessToken}`);
  }
  return fetch(input, { ...init, headers });
}
