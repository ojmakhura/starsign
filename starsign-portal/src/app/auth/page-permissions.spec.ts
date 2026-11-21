import { hasPermission } from './page-permissions';

describe('hasPermission', () => {
  it('allows pages without requirements', () => {
    expect(hasPermission([], [])).toBe(true);
  });

  it('accepts the owner-scoped variant of a scope', () => {
    expect(hasPermission(['documents:list'], ['documents:list-own'])).toBe(true);
  });

  it('needs every scope of a nested entry', () => {
    expect(hasPermission([['workflows:edit', 'documents:view']], ['workflows:edit'])).toBe(false);
    expect(hasPermission([['workflows:edit', 'documents:view']], ['workflows:edit-own', 'documents:view'])).toBe(true);
  });

  it('denies missing scopes', () => {
    expect(hasPermission(['workflows:cancel'], ['workflows:view'])).toBe(false);
  });
});
