import { register } from 'node:module';
register('./airbnb-loader.mjs', import.meta.url);
await import('../../upstreams/airbnb/dist/index.js');
