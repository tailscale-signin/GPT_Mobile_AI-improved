"""Browser request paths from the Windows 403 regression log."""
import importlib
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi.responses import JSONResponse, Response
from v14.browser import inject_bootstrap, SCRIPT_TAG
import test_gateway_v14 as v14_tests


class BrowserTests(unittest.TestCase):
    setUpClass = classmethod(v14_tests.RuntimeTests.setUpClass.__func__)
    setUp = v14_tests.RuntimeTests.setUp
    tearDown = v14_tests.RuntimeTests.tearDown
    def upstream(self, method, target, **kwargs):
        if method == 'GET' and target.endswith('/'):
            body = '<html><head><script type="module" src="/app.js"></script></head><body>UI</body></html>'
            return Mock(status_code=200, content=body.encode(), headers={'content-type':'text/html','etag':'old'})
        self.assertNotIn('x-gateway-csrf', {k.lower() for k in kwargs.get('headers', {})})
        return Mock(status_code=200, content=b'{"ok":true}', headers={'content-type':'application/json'})

    def test_html_bootstrap_precedes_app_and_static_script_is_served(self):
        with patch.object(self.gateway.runtime, 'http_request', side_effect=self.upstream):
            response = self.client.get('/')
        self.assertEqual(response.status_code, 200)
        self.assertLess(response.text.index(SCRIPT_TAG), response.text.index('type="module"'))
        self.assertNotIn('etag', response.headers)
        script = self.client.get('/gateway/browser-bootstrap.js')
        self.assertEqual(script.status_code, 200)
        self.assertIn('X-Gateway-CSRF', script.text)
        self.assertEqual(script.headers['cache-control'], 'no-store')

    def test_logged_posts_work_with_bridge_token_and_token_stays_at_gateway(self):
        origin = {'Origin':'http://127.0.0.1:8090', 'Sec-Fetch-Site':'same-origin'}
        rejected = self.client.post('/tools', headers=origin, json={})
        self.assertEqual(rejected.status_code, 403)
        self.assertEqual(rejected.headers['x-gateway-admission-error'], 'browser_csrf_required')
        token = self.client.get('/gateway/session', headers=origin).json()['csrfToken']
        headers = dict(origin, **{'X-Gateway-CSRF':token})
        with patch.object(self.gateway.runtime, 'http_request', side_effect=self.upstream) as proxy:
            for path in ['/tools', '/v1/streams/lookup']:
                response = self.client.post(path, headers=headers, json={'model':'local'})
                self.assertEqual(response.status_code, 200)
            self.assertEqual(proxy.call_count, 2)
        async def completion(request):
            return JSONResponse({'choices':[{'message':{'content':'browser reply'}}]})
        with patch.object(self.gateway.runtime, 'chat_completions', side_effect=completion):
            response = self.client.post('/v1/chat/completions', headers=headers,
                json={'model':'local','messages':[{'role':'user','content':'hello'}],'stream':False})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['choices'][0]['message']['content'], 'browser reply')

    def test_cross_origin_post_never_dispatches_even_with_valid_token(self):
        token = self.client.get('/gateway/session').json()['csrfToken']
        with patch.object(self.gateway.runtime, 'http_request') as proxy:
            response = self.client.post('/tools', headers={'Origin':'https://evil.example','X-Gateway-CSRF':token},json={})
        self.assertEqual(response.status_code, 403)
        proxy.assert_not_called()

    def test_non_html_and_duplicate_injection_do_not_change_content(self):
        response=Response(b'{"data":1}',media_type='application/json')
        self.assertIs(inject_bootstrap(response),response)
        html=Response('<HEAD><script src="app.js"></script></HEAD>',media_type='text/html')
        first=inject_bootstrap(html)
        self.assertIs(inject_bootstrap(first),first)
        self.assertEqual(first.body.count(SCRIPT_TAG.encode()),1)
