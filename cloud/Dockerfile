FROM node:22-alpine AS unpack
WORKDIR /src
COPY zoolchat-server-v0.27.tar.gz.b64 /tmp/server.b64
RUN base64 -d /tmp/server.b64 > /tmp/server.tar.gz && tar -xzf /tmp/server.tar.gz -C /src

FROM node:22-alpine AS build
WORKDIR /app
COPY --from=unpack /src/server/ ./
RUN npm install --no-audit --no-fund && npm run build

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY --from=unpack /src/server/package*.json ./
RUN npm install --omit=dev --no-audit --no-fund
COPY --from=build /app/dist ./dist
COPY --from=unpack /src/server/migrations ./migrations
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||8080)+'/health').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"
CMD ["npm","start"]
