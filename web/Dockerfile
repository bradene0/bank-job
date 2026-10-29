# syntax=docker/dockerfile:1
ARG NODE=node:26.13.0-alpine
ARG NGINX=nginxinc/nginx-unprivileged:1.30.6-alpine

FROM ${NODE} AS build
# Node 25+ no longer ships corepack; install the pinned pnpm from package.json.
RUN npm install -g pnpm@10.33.0
WORKDIR /app
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

# Unprivileged nginx: runs as uid 101 and listens on 8080.
FROM ${NGINX} AS runtime
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
