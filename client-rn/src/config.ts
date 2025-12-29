const DEV_SERVER_BASE_URL = 'https://connected.dowhile.fun';
const DEV_WS_URL = 'wss://connected.dowhile.fun/ws';

const PROD_SERVER_BASE_URL = 'https://connected.dowhile.fun';
const PROD_WS_URL = 'wss://connected.dowhile.fun/ws';

export const config = {
  apiBaseUrl: __DEV__ ? DEV_SERVER_BASE_URL : PROD_SERVER_BASE_URL,
  wsUrl: __DEV__ ? DEV_WS_URL : PROD_WS_URL,
  shareBaseUrl: __DEV__ ? DEV_SERVER_BASE_URL : PROD_SERVER_BASE_URL,
};
