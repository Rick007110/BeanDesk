# The BeanDesk API (see deploy/ and docs/vps.md). Node runs the TypeScript directly.
FROM node:24.19.0-alpine
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY src ./src
USER node
CMD ["node", "src/main.ts"]
