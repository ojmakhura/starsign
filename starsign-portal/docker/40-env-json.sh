#!/bin/sh
# Write the runtime config the portal loads (public/env_template.json -> env.json)
set -eu
html=/usr/share/nginx/html
envsubst '${STARSIGN_API_URL} ${STARSIGN_PORTAL_URL} ${KEYCLOAK_URL} ${KEYCLOAK_REALM} ${KEYCLOAK_CLIENT_ID}' \
  < "$html/env_template.json" > "$html/env.json"
echo "40-env-json.sh: wrote $html/env.json"
