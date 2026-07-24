# Present and buildable even though this service runs on Lambda today. Used
# directly by docker-compose for local dev.
FROM node:22-slim
WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev
COPY src ./src
ENV PORT=3000
EXPOSE 3000
CMD ["node", "src/adapters/httpServer.js"]
