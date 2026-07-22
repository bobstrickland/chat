# Local-dev only. Stands in for API Gateway WebSocket — never deployed.
# Node 22 to match the rest of the stack (CLAUDE.md).

FROM node:22-slim

WORKDIR /app

COPY package.json ./
RUN npm install --omit=dev

COPY server.js ./

ENV PORT=8090
EXPOSE 8090

CMD ["node", "server.js"]
