FROM node:22-alpine AS unpack
WORKDIR /src
COPY server-v032.tar.gz.b64 /tmp/server.b64
COPY server-v032.sha256 /tmp/server.sha256
RUN base64 -d /tmp/server.b64 > /tmp/server.tar.gz \
    && echo "$(cat /tmp/server.sha256)  /tmp/server.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/server.tar.gz -C /src

FROM node:22-alpine AS build
WORKDIR /app
COPY --from=unpack /src/package*.json ./
RUN npm ci --no-audit --no-fund
COPY --from=unpack /src/tsconfig.json ./
COPY --from=unpack /src/src ./src
COPY --from=unpack /src/migrations ./migrations
RUN npm run build

FROM node:22-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production
COPY --from=unpack /src/package*.json ./
RUN npm ci --omit=dev --no-audit --no-fund \
    && mkdir -p /app/data/media \
    && chown -R node:node /app
COPY --from=build --chown=node:node /app/dist ./dist
COPY --from=unpack --chown=node:node /src/migrations ./migrations
USER node
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||8080)+'/health').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"
CMD ["npm","start"]
