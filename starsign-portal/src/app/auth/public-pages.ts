import { ActivatedRouteSnapshot } from '@angular/router';

/**
 * Pages that work without the portal role: organisation sign-up (signed out), accepting
 * an invitation (signed in, but not yet a member of any organisation) and the external
 * signing portal (/sign/{token}, signed out: the token is the signatory's credential).
 * They render without the sidebar and skip PortalAccessService's role check.
 */
export const PUBLIC_PATHS = ['/register', '/invitations/accept', '/sign'] as const;

/** Whether a browser path (no query or fragment) is one of the public pages. */
export const isPublicPath = (path: string): boolean =>
  PUBLIC_PATHS.some((publicPath) => path === publicPath || path.startsWith(`${publicPath}/`));

/** Whether the deepest activated route is marked `data: { public: true }`. */
export const isPublicRoute = (root: ActivatedRouteSnapshot): boolean => {
  let route: ActivatedRouteSnapshot | null = root;
  while (route?.firstChild) {
    route = route.firstChild;
  }
  return !!route?.data['public'];
};
