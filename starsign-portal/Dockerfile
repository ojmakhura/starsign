# StarSign portal: Angular build served by nginx
# Build context: starsign-portal/

FROM node:24-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npx ng build --configuration production

FROM nginx:1.29-alpine
# /api is proxied to the webservice; env.json is written from env_template.json at start
COPY docker/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY docker/40-env-json.sh /docker-entrypoint.d/40-env-json.sh
COPY --from=build /src/dist/starsign-portal/browser /usr/share/nginx/html
RUN chmod +x /docker-entrypoint.d/40-env-json.sh

ENV STARSIGN_API_URL=/api \
    STARSIGN_PORTAL_URL=http://localhost:4700 \
    KEYCLOAK_URL=http://localhost:8080 \
    KEYCLOAK_REALM=starsign \
    KEYCLOAK_CLIENT_ID=starsign-portal \
    API_UPSTREAM=http://webservice:8085 \
    NGINX_RESOLVER=127.0.0.11

EXPOSE 80
