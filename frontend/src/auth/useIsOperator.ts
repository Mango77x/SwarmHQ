import { useAuth } from "react-oidc-context";

// Client-side only, for UI gating (hide/disable buttons an OBSERVER would
// otherwise get a 403 clicking) - never a security boundary by itself,
// the backend's SecurityConfig is what actually enforces this, exactly as
// it should be: a decoded-but-unverified read of a JWT is fine to trust
// for "should this button be enabled", never for an actual authorization
// decision.
function realmRoles(accessToken: string | undefined): string[] {
  const payload = accessToken?.split(".")[1];
  if (!payload) return [];
  try {
    const json = JSON.parse(atob(payload.replace(/-/g, "+").replace(/_/g, "/")));
    return (json.realm_access?.roles as string[] | undefined) ?? [];
  } catch {
    return [];
  }
}

export function useIsOperator(): boolean {
  const auth = useAuth();
  return realmRoles(auth.user?.access_token).includes("OPERATOR");
}
