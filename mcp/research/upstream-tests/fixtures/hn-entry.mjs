import axios from '../../upstreams/hn-server/node_modules/axios/index.js';

axios.defaults.adapter = async config => {
  if (config.url !== 'https://news.ycombinator.com/new') throw new Error('Unexpected provider request in dependency fixture');
  return {
    status: 200, statusText: 'OK', headers: {}, config,
    data: '<table><tr class="athing"><td class="rank">1.</td><td class="titleline"><a href="item?id=123">Dependency fixture</a></td></tr><tr><td><span class="score">42 points</span><a class="hnuser">fixture</a><span class="age" title="2026-11-15T00:00:00"></span><a>7 comments</a></td></tr></table>'
  };
};
await import('../../upstreams/hn-server/build/index.js');
