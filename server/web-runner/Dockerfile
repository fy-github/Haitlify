FROM mcr.microsoft.com/playwright:v1.61.0-jammy

WORKDIR /app

COPY package.json package-lock.json* ./
RUN npm install --omit=dev

# server.mjs 运行时依赖全部顶层 .mjs 模块(browser-launcher / run-* / login-discovery / page-locator-discovery / web-runner-server 等)
COPY *.mjs ./
COPY fixtures ./fixtures

ENV APP_WEB_AUTOMATION_RUNNER_PORT=9323
ENV APP_WEB_AUTOMATION_RUNNER_BROWSER_CHANNEL=chromium
ENV APP_WEB_AUTOMATION_RUNNER_HEADLESS=true
ENV APP_WEB_AUTOMATION_RUNNER_STRICT_STARTUP=false

EXPOSE 9323

CMD ["node", "server.mjs"]
