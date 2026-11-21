/** Runtime configuration loaded from /env.json before bootstrap. */
export interface Env {
  apiUrl: string;
  authDomain: string;
  realm: string;
  clientId: string;
  redirectUri: string;
  /** Client role on `clientId` a user needs to use the portal (PORTAL_USER); blank disables the check. */
  portalRole?: string;
}
