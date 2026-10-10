import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { runInNewContext } from 'node:vm';

const source = readFileSync(new URL('../v14/browser-bootstrap.js', import.meta.url), 'utf8');
const base = 'http://127.0.0.1:8090';
const firstToken = 'a'.repeat(64);
const nextToken = 'b'.repeat(64);
function browser(fetch) {
    const window = { fetch, location: { href: base + '/', origin: base } };
    runInNewContext(source, { window, Request, Response, Headers, URL, Promise });
    return window;
}
const ok = () => new Response('{"ok":true}', { headers: { 'Content-Type':'application/json' } });
const session = token => new Response(JSON.stringify({ csrfToken: token }));

test('the three logged mutation endpoints receive the token and original JSON', async () => {
    let sessions = 0;
    const mutations = [];
    const window = browser(async input => {
        if (input === '/gateway/session') { sessions++; return session(firstToken); }
        const request = input;
        mutations.push({ url: request.url, token: request.headers.get('X-Gateway-CSRF'), body: await request.json(), custom: request.headers.get('X-Test') });
        return ok();
    });
    for (const path of ['/tools','/v1/streams/lookup','/v1/chat/completions']) {
        const result = await window.fetch(path, { method:'POST', headers:{ 'Content-Type':'application/json', 'X-Test':'kept' }, body:JSON.stringify({ path }) });
        assert.equal(result.status,200);
    }
    assert.equal(sessions,1);
    assert.equal(mutations.length,3);
    for (const item of mutations) {
        assert.equal(item.token,firstToken);
        assert.equal(item.custom,'kept');
        assert.equal(item.url,base + item.body.path);
    }
});

test('concurrent writes share one session lookup', async () => {
    let sessions=0;
    const window=browser(async input => {
        if(input === '/gateway/session') { sessions++; await new Promise(resolve => setTimeout(resolve,5)); return session(firstToken); }
        return ok();
    });
    await Promise.all(['/tools','/v1/streams/lookup'].map(path => window.fetch(path,{method:'POST',body:'{}'})));
    assert.equal(sessions,1);
});

test('external requests and reads are untouched and never acquire a token',async () => {
    const seen=[];
    const window=browser(async (input,init) => { seen.push({input,init}); return ok(); });
    const init={method:'POST',body:'outside'};
    await window.fetch('https://external.example/api',init);
    await window.fetch('/v1/models');
    assert.equal(seen.length,2);
    assert.equal(seen[0].input,'https://external.example/api');
    assert.equal(seen[0].init,init);
    assert.equal(seen[1].input,'/v1/models');
});

test('a stale session is refreshed only on explicit pre-dispatch admission failure',async () => {
    let sessions=0;
    const calls=[];
    const window=browser(async input => {
        if(input === '/gateway/session') return session(++sessions === 1 ? firstToken : nextToken);
        calls.push({token:input.headers.get('X-Gateway-CSRF'),body:await input.text()});
        return calls.length===1 ? new Response('{}',{status:403,headers:{'X-Gateway-Admission-Error':'browser_csrf_required'}}) : ok();
    });
    assert.equal((await window.fetch('/tools',{method:'POST',body:'original'})).status,200);
    assert.deepEqual(calls,[{token:firstToken,body:'original'},{token:nextToken,body:'original'}]);
    assert.equal(sessions,2);
});

test('ordinary upstream rejection and network failures never replay mutations',async () => {
    let calls=0;
    const window=browser(async input => {
        if(input === '/gateway/session') return session(firstToken);
        calls++;
        return new Response('{}',{status:403});
    });
    assert.equal((await window.fetch('/tools',{method:'POST'})).status,403);
    assert.equal(calls,1);
    const broken=browser(async input => {
        if(input === '/gateway/session') return session(firstToken);
        calls++;
        throw new Error('network gone');
    });
    await assert.rejects(broken.fetch('/tools',{method:'POST'}),/network gone/);
    assert.equal(calls,2);
});

test('Request input preserves body and cancellation signal', async () => {
    const controller=new AbortController();
    const request=new Request(base+'/v1/chat/completions',{method:'POST',body:'original',signal:controller.signal});
    const window=browser(async input => {
        if(input === '/gateway/session') return session(firstToken);
        assert.equal(await input.text(),'original');
        controller.abort();
        assert.equal(input.signal.aborted,true);
        return ok();
    });
    await window.fetch(request);
});

test('session failure blocks writes instead of bypassing CSRF',async () => {
    let calls=0;
    const window=browser(async input => {
        if(input === '/gateway/session') return new Response('{}',{status:401});
        calls++; return ok();
    });
    await assert.rejects(window.fetch('/tools',{method:'POST'}),/session unavailable/);
    assert.equal(calls,0);
});
