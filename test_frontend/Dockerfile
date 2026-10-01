# TriPin 테스트 프론트 — 로컬 개발 스택(../compose.dev.yaml)용 Vite dev server.
# BACKEND_URL·AI_URL·VITE_HOST는 compose가 환경변수로 넘긴다(.env.local보다 우선).
FROM node:22-alpine
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .
EXPOSE 5173
CMD ["npm", "run", "dev"]
