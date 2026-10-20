FROM node:24.11.0-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY index.html vite.config.ts tsconfig.json ./
COPY src src
COPY public public
RUN npm run build
FROM nginx:1.28.0-alpine
COPY nginx.conf /etc/nginx/templates/default.conf.template
ENV NGINX_ENVSUBST_FILTER=^BACKEND_UPSTREAM$ BACKEND_UPSTREAM=backend:8090
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
