#!/usr/bin/env bash
# Create or update the StarSign realm on a running Keycloak through the Admin REST API.
#
# Usage (from the repository root):
#   KEYCLOAK_URL=https://auth.example.com \
#   KEYCLOAK_ADMIN=admin KEYCLOAK_ADMIN_PASSWORD=... \
#   keycloak/import-realm.sh [realm-file]          (default: starsign-realm.json)
#
# The realm file holds concrete values (portal URL, client secret, SMTP, bootstrap admin);
# change them in keycloak/generate_authz.py and regenerate.
#
# New realm:      POST /admin/realms with the whole file (bootstrap admin included).
# Existing realm: realm settings are updated, roles and the StarSign clients are imported with
#                 OVERWRITE (stable ids keep their service account), and the authorization
#                 settings of starsign-api are replaced. Existing users are never touched.
#
# For a fresh container, mounting the file and starting with `kc.sh start --import-realm`
# does the same as the "new realm" path.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REALM_FILE="${1:-$ROOT/starsign-realm.json}"
REALM="starsign"
API_CLIENT="starsign-api"
: "${KEYCLOAK_URL:?set KEYCLOAK_URL, e.g. http://localhost:8080}"
: "${KEYCLOAK_ADMIN:?set KEYCLOAK_ADMIN (master realm administrator)}"
: "${KEYCLOAK_ADMIN_PASSWORD:?set KEYCLOAK_ADMIN_PASSWORD}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cp "$REALM_FILE" "$WORK/realm.json"

token() {
  curl -fsS -X POST "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" \
    --data-urlencode "grant_type=password" --data-urlencode "client_id=admin-cli" \
    --data-urlencode "username=$KEYCLOAK_ADMIN" --data-urlencode "password=$KEYCLOAK_ADMIN_PASSWORD" \
    | jq -r .access_token
}
TOKEN="$(token)"
api() { # method path [json-file]
  local args=(-fsS -X "$1" -H "Authorization: Bearer $TOKEN" "$KEYCLOAK_URL/admin$2")
  [[ $# -ge 3 ]] && args+=(-H "Content-Type: application/json" --data-binary "@$3")
  curl "${args[@]}"
}

status="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$KEYCLOAK_URL/admin/realms/$REALM")"
if [[ "$status" == "404" ]]; then
  api POST /realms "$WORK/realm.json"
  echo "Created realm $REALM"
  exit 0
fi
[[ "$status" == "200" ]] || { echo "import-realm.sh: GET realm returned HTTP $status" >&2; exit 1; }

# Realm settings: everything except the collections imported below
jq 'del(.roles, .clients, .users, .components, .organizations, .defaultRole)' "$WORK/realm.json" > "$WORK/settings.json"
api PUT "/realms/$REALM" "$WORK/settings.json"

# Roles and StarSign clients (authorization settings are imported separately)
jq '{ifResourceExists: "OVERWRITE",
     roles: .roles,
     clients: [.clients[] | del(.authorizationSettings)]}' "$WORK/realm.json" > "$WORK/partial.json"
api POST "/realms/$REALM/partialImport" "$WORK/partial.json" | jq -c '{added, overwritten, skipped}'

# Declarative user profile (custom tenant attributes)
jq -r '.components["org.keycloak.userprofile.UserProfileProvider"][0].config["kc.user.profile.config"][0]' \
  "$WORK/realm.json" > "$WORK/user-profile.json"
api PUT "/realms/$REALM/users/profile" "$WORK/user-profile.json" > /dev/null

# Authorization settings of starsign-api
CLIENT_UUID="$(curl -fsS -H "Authorization: Bearer $TOKEN" \
  "$KEYCLOAK_URL/admin/realms/$REALM/clients?clientId=$API_CLIENT" | jq -r '.[0].id')"
jq '.clients[] | select(.clientId=="starsign-api") | .authorizationSettings' "$WORK/realm.json" > "$WORK/authz.json"
api POST "/realms/$REALM/clients/$CLIENT_UUID/authz/resource-server/import" "$WORK/authz.json"

# Service account roles on realm-management
SA_ID="$(curl -fsS -H "Authorization: Bearer $TOKEN" \
  "$KEYCLOAK_URL/admin/realms/$REALM/clients/$CLIENT_UUID/service-account-user" | jq -r .id)"
RM_UUID="$(curl -fsS -H "Authorization: Bearer $TOKEN" \
  "$KEYCLOAK_URL/admin/realms/$REALM/clients?clientId=realm-management" | jq -r '.[0].id')"
WANTED="$(jq -c '[.users[] | select(.serviceAccountClientId=="starsign-api") | .clientRoles["realm-management"][]]' "$WORK/realm.json")"
curl -fsS -H "Authorization: Bearer $TOKEN" \
  "$KEYCLOAK_URL/admin/realms/$REALM/clients/$RM_UUID/roles?max=500" \
  | jq --argjson wanted "$WANTED" '[.[] | select(.name as $n | $wanted | index($n))]' > "$WORK/sa-roles.json"
api POST "/realms/$REALM/users/$SA_ID/role-mappings/clients/$RM_UUID" "$WORK/sa-roles.json"

echo "Updated realm $REALM (users untouched)"
