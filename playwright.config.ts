import {defineConfig} from '@playwright/test';

const webPort=Number(process.env.FOODFLOW_TEST_WEB_PORT??5181);

export default defineConfig({
 testDir:'./tests',
 globalSetup:'./tests/global-setup.mjs',
 use:{baseURL:`http://127.0.0.1:${webPort}`,browserName:'chromium'},
});
