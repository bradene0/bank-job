# syntax=docker/dockerfile:1
# Build context: the repo root, because the game bundles content/ (tile labels and level rules;
# never the prompt fragments) at build time:
#   docker build -f web/Dockerfile -t bankjob/web .
ARG NODE=node:26.13.0-alpine
ARG NGINX=nginxinc/nginx-unprivileged:1.30.6-alpine

FROM ${NODE} AS build
# Node 25+ no longer ships corepack; install the pinned pnpm from package.json.
RUN npm install -g pnpm@10.33.0
WORKDIR /src/web
COPY web/package.json web/pnpm-lock.yaml web/pnpm-workspace.yaml ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store pnpm install --frozen-lockfile
COPY content/ /src/content/
COPY web/ ./
# The public API and wake URLs are baked in at build time (empty: local defaults).
ARG VITE_API_URL
ARG VITE_WAKE_URL
RUN pnpm build && pnpm check:bundle

# Unprivileged nginx: runs as uid 101 and listens on 8080.
FROM ${NGINX} AS runtime
COPY web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/web/dist /usr/share/nginx/html
EXPOSE 8080
