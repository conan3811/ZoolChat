FROM node:22-alpine AS unpack
WORKDIR /src
COPY parts/ /tmp/parts/
COPY v0_30/ /tmp/v0_30/
COPY v0_31/ /tmp/v0_31/
RUN cat /tmp/parts/part00.b64 /tmp/parts/part01.b64 /tmp/parts/part02.b64 /tmp/parts/part03.b64 /tmp/parts/part04.b64 /tmp/parts/part05.b64 /tmp/parts/part06.b64 > /tmp/server.b64 \
    && base64 -d /tmp/server.b64 > /tmp/server.tar.gz \
    && echo "c9575e33691bbb48074f3d8e03018c3e760dde46fe736b2b6d351c36653306b8  /tmp/server.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/server.tar.gz -C /src \
    && cat /tmp/v0_30/cloud_part_00.b64 /tmp/v0_30/cloud_part_01.b64 /tmp/v0_30/cloud_part_02.b64 > /tmp/v030.b64 \
    && base64 -d /tmp/v030.b64 > /tmp/v030.tar.gz \
    && echo "db03057803bc3b8721dfd2f40abdeb55a15ab21cf5ebb5fcd941e6b2ee996dec  /tmp/v030.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/v030.tar.gz -C /src/server \
    && sh /tmp/v0_30/postfix.sh /src/server/src/index.ts \
    && cat /tmp/v0_31/part_00.b64 /tmp/v0_31/part_01.b64 /tmp/v0_31/part_02.b64 /tmp/v0_31/part_03.b64 /tmp/v0_31/part_04.b64 > /tmp/v031.b64 \
    && base64 -d /tmp/v031.b64 > /tmp/v031.tar.gz \
    && echo "ade435a69c499ba20bb82bfa409a16fde11841bb108908a618d99d6eca26e518  /tmp/v031.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/v031.tar.gz -C /src/server

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
