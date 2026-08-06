import { useEffect } from "react";
import { useAuth } from "react-oidc-context";
import { setAccessToken } from "./api/http";
import KpiBar from "./components/KpiBar";
import ModeToggle from "./components/ModeToggle";
import TacticalMap from "./components/TacticalMap";

function App() {
  const auth = useAuth();

  // Every api/*.ts call goes through apiFetch (see api/http.ts), which
  // reads whatever token was set here last - kept in sync with
  // react-oidc-context's own state rather than each api module reaching
  // into the auth context itself, so none of them need to know Keycloak
  // exists.
  useEffect(() => {
    setAccessToken(auth.user?.access_token ?? null);
  }, [auth.user]);

  if (auth.isLoading) {
    return (
      <div className="flex h-screen items-center justify-center bg-slate-950 text-sm text-slate-400">
        Connecting to Keycloak…
      </div>
    );
  }

  if (!auth.isAuthenticated) {
    return (
      <div className="flex h-screen flex-col items-center justify-center gap-4 bg-slate-950 text-slate-100">
        <div className="text-lg font-semibold tracking-wide">SwarmHQ</div>
        <div className="text-sm text-slate-400">Sign in to access the tactical map</div>
        <button
          type="button"
          onClick={() => auth.signinRedirect()}
          className="rounded bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-500"
        >
          Sign in
        </button>
        {auth.error && <div className="text-xs text-red-400">{auth.error.message}</div>}
      </div>
    );
  }

  return (
    <div className="flex h-screen flex-col">
      <header className="flex items-center gap-3 border-b border-slate-800 bg-slate-950 px-4 py-2">
        <span className="text-sm font-semibold tracking-wide text-slate-100">
          SwarmHQ
        </span>
        <span className="text-xs text-slate-500">tactical map</span>
        <ModeToggle />
        <KpiBar />
        <div className="ml-auto flex items-center gap-2 text-xs text-slate-400">
          <span title="Signed in as">{auth.user?.profile.preferred_username}</span>
          <button
            type="button"
            onClick={() => auth.signoutRedirect({ post_logout_redirect_uri: window.location.origin })}
            className="rounded border border-slate-700 px-2 py-1 text-slate-300 hover:border-slate-500"
          >
            Sign out
          </button>
        </div>
      </header>
      <main className="flex-1">
        <TacticalMap />
      </main>
    </div>
  );
}

export default App;
