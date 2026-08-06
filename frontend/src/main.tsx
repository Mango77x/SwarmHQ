import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { AuthProvider } from 'react-oidc-context'
import './index.css'
import App from './App.tsx'

// Keycloak is always reached at its own host-mapped port regardless of
// where the frontend itself is served from (Vite dev on 5173, or the
// bundled jar on 8080) - see docker-compose.yml/PROJECT_OVERVIEW.md's
// "Hardening & parity layer". redirect_uri is the current origin, which
// works for both since the realm's client whitelists both.
const oidcConfig = {
  authority: 'http://localhost:8081/realms/swarmhq',
  client_id: 'swarmhq-frontend',
  redirect_uri: window.location.origin,
  scope: 'openid profile',
  onSigninCallback: () => {
    window.history.replaceState({}, document.title, window.location.pathname)
  },
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AuthProvider {...oidcConfig}>
      <App />
    </AuthProvider>
  </StrictMode>,
)
