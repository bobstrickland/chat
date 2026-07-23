# Deployable artifact for the web client: a static SPA bundle served by nginx.
#
# Unlike the backend services, this is NOT a Lambda/Fargate wrapper — it's a
# static build. In a real deploy the bundle sits behind CloudFront/API Gateway,
# which handles routing /auth and /profiles to the backends (the dev-time job of
# proxy.conf.json). This image just serves the files; it does not proxy.
#
# LOCAL DEV DOES NOT USE THIS IMAGE — run `npm start` on the host instead, which
# runs `ng serve` with the dev proxy. See README.md.

# ---- build ----
FROM node:22-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# ---- serve ----
FROM nginx:1.27-alpine
# SPA fallback: unknown paths serve index.html so client-side routing works.
RUN printf 'server {\n\
  listen 80;\n\
  root /usr/share/nginx/html;\n\
  location / { try_files $uri $uri/ /index.html; }\n\
}\n' > /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/web/browser /usr/share/nginx/html
EXPOSE 80
