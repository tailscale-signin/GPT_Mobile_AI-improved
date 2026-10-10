"""V14 admission, real runtime, deterministic evidence and distribution gates."""
import asyncio
import importlib
import json
import os
import shutil
import sys
import tempfile
import threading
import time
import unittest
from collections import deque
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi import FastAPI, Request
from fastapi.testclient import TestClient
from gateway_security import DeviceStore, current_device
from v14.config import Config
from v14.identity import Admission
from v14.package import validate_package
from v14.research import run_searches, url_identity
from v14.memory import apply_client_recall
from v14.jobs import migrate_legacy_owner


class AdmissionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = DeviceStore(Path(self.temp.name) / 'auth.db')

    def tearDown(self):
        self.temp.cleanup()

    def client(self, config=None, peer='127.0.0.1'):
        app = FastAPI()
        @app.api_route('/{path:path}', methods=['GET', 'POST'])
        def echo(path: str):
            return {'principal': current_device.get()}
        app.add_middleware(Admission, config=(config or Config()).validate(), store=self.store)
        return TestClient(app, base_url='http://127.0.0.1:8090', client=(peer, 1234))

    def test_keyfree_loopback_and_nonloopback_denial(self):
        self.assertEqual(self.client().get('/v1/models').json()['principal'], 'local:single-user')
        self.assertEqual(self.client(peer='100.64.0.5').get('/v1/models').status_code, 401)

    def test_spoofed_forwarding_and_invalid_origin_host(self):
        c = self.client()
        for headers in [{'Tailscale-User-Login':'owner'}, {'X-Forwarded-For':'127.0.0.1'}, {'Host':'evil'}, {'Origin':'https://evil'}]:
            self.assertIn(c.get('/v1/models', headers=headers).status_code, (401,403))

    def test_browser_mutation_requires_same_origin_csrf(self):
        c = self.client()
        h = {'Origin':'http://127.0.0.1:8090'}
        self.assertEqual(c.post('/v1/chat/completions', headers=h).status_code, 403)
        h['X-Gateway-CSRF'] = c.get('/gateway/session', headers=h).json()['csrfToken']
        self.assertEqual(c.post('/v1/chat/completions', headers=h).status_code, 200)
        self.assertEqual(c.get('/v1/models').headers['cache-control'], 'no-store')

    def test_proxy_requires_approved_identity_and_hides_admin(self):
        config = Config(mode='trusted_proxy', trusted_users={'owner@example.com': {'principalId':'owner','scopes':['chat','jobs','tools']}})
        c = self.client(config)
        self.assertEqual(c.get('/v1/models').status_code, 401)
        h = {'Tailscale-User-Login':'owner@example.com'}
        self.assertEqual(c.get('/v1/models',headers=h).json()['principal'],'tailnet:owner')
        self.assertEqual(c.post('/gateway/mcp/restart/private',headers=h).status_code,403)
        self.assertEqual(c.get('/gateway/health',headers=h).status_code,403)
        self.assertEqual(c.post('/props',headers=h).status_code,403)

    def test_job_ownership_ignores_client_installation_id(self):
        self.store.claim('private', 'tailnet:other')
        c = self.client()
        for path in ['/gateway/jobs/private', '/v1/gateway/jobs/private/result', '/gateway/jobs/private/events']:
            self.assertEqual(c.get(path,headers={'X-Device-ID':'tailnet:other'}).status_code,404)
        self.assertEqual(c.post('/gateway/jobs/private/cancel').status_code,404)

    def test_config_rejects_all_interface_and_empty_proxy_grants(self):
        with self.assertRaises(ValueError): Config(host='0.0.0.0').validate()
        with self.assertRaises(ValueError): Config(mode='trusted_proxy').validate()

    def test_explicit_legacy_ownership_migration(self):
        device, token = self.store.issue('old phone')
        self.store.claim('old', device)
        self.assertFalse(self.store.owns('old', 'local:single-user'))
        self.assertEqual(migrate_legacy_owner(self.store, device, 'local:single-user'), 1)
        self.assertTrue(self.store.owns('old', 'local:single-user'))
        self.assertEqual(self.store.authenticate(token), device)


class ResearchTests(unittest.TestCase):
    def test_url_identity_preserves_semantic_differences(self):
        self.assertEqual(url_identity('https://EXAMPLE.com/A/?q=a%2Fb&x=1&utm_source=z#part'), 'https://example.com/A/?q=a%2Fb&x=1#part')
        self.assertNotEqual(url_identity('https://example.com/A'), url_identity('https://example.com/A/'))
        self.assertNotEqual(url_identity('https://example.com/a#one'), url_identity('https://example.com/a#two'))
        self.assertIsNone(url_identity('https://user:secret@example.com/'))
        self.assertIsNone(url_identity('file:///tmp/x'))

    def test_completion_order_cannot_change_sources_or_attribution(self):
        targets = [('a', {'count':10}), ('b', {'count':10})]
        def search(fast):
            def execute(name,args,cancel_event=None):
                time.sleep(.002 if name == fast else .015)
                return {'results':[{'url':'https://example.com/common?utm_source='+name,'title':'common'}, {'url':'https://example.com/'+name,'title':name}]}
            return json.loads(run_searches(targets,execute)['content'][0]['text'])
        a,b = search('a'),search('b')
        self.assertEqual(a,b)
        self.assertEqual(a['results'][0]['foundBy'],['a','b'])
        self.assertEqual([s['title'] for s in a['results']],['common','a','b'])

    def test_failure_preserves_other_engine_and_large_json_is_valid(self):
        def execute(name,args,cancel_event=None):
            if name == 'bad': raise RuntimeError('sensitive secret')
            return {'padding':'x'*10000, 'results':[{'url':'https://example.com/','snippet':'valid'}]}
        result = run_searches([('bad',{}),('ok',{})],execute)
        body = json.loads(result['content'][0]['text'])
        self.assertFalse(result['isError'])
        self.assertEqual(len(body['results']),1)
        self.assertNotIn('sensitive secret',json.dumps(body))
        self.assertTrue(body['engines'][1]['rawEvidenceOmitted'])

    def test_cancel_before_dispatch_does_not_invoke(self):
        flag=threading.Event(); flag.set()
        with self.assertRaises(InterruptedError):
            run_searches([('x',{})], lambda *a,**k: self.fail('dispatched'),cancelled=flag)


class MemoryTests(unittest.TestCase):
    def payload(self):
        return {'messages':[{'role':'user','content':'hello'}], 'gateway_memory':{'authority':'client','protocolVersion':1,'conversationScope':'c',
                'records':[{'factId':'f','revision':1,'scope':'c','allowedDestinations':['local'],'content':'fact','sensitivity':'high'}]}}

    def test_local_recall_preserves_client_authority_and_input(self):
        payload=self.payload()
        result=apply_client_recall(payload,'http://127.0.0.1:8080')
        self.assertIn('fact',result['messages'][0]['content'])
        self.assertIn('gateway_memory',payload)
        self.assertNotIn('gateway_memory',result)

    def test_cloud_and_cross_conversation_recall_denied(self):
        with self.assertRaises(PermissionError): apply_client_recall(self.payload(),'https://cloud.example')
        payload=self.payload(); payload['gateway_memory']['records'][0]['scope']='other'
        with self.assertRaises(PermissionError): apply_client_recall(payload,'http://127.0.0.1:8080')


class RuntimeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.gateway=importlib.import_module('gateway_v14')

    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.store=DeviceStore(Path(self.temp.name)/'auth.db')
        self.patches=[patch.object(self.gateway.runtime,'device_store',self.store),
                      patch('v14.identity.device_store',self.store),
                      patch.object(self.gateway.runtime,'start_gateway_background_services'),
                      patch.object(self.gateway.runtime,'durable_persist_job'),
                      patch.object(self.gateway.runtime,'cleanup_job_registry')]
        for p in self.patches: p.start()
        self.gateway.app.middleware_stack=None
        self.client=TestClient(self.gateway.app,base_url='http://127.0.0.1:8090',client=('127.0.0.1',2222))
        self.client.__enter__()

    def tearDown(self):
        self.client.__exit__(None,None,None)
        for p in reversed(self.patches): p.stop()
        self.gateway.app.middleware_stack=None
        self.gateway.runtime.job_registry.clear()
        self.temp.cleanup()

    def test_actual_capabilities_ready_and_facade(self):
        cap=self.client.get('/v1/gateway/capabilities').json()
        fixture=json.loads((Path(__file__).resolve().parents[1]/'contracts/v14/capabilities.json').read_text(encoding="utf-8"))
        for key,value in fixture.items():
            if key == "features":
                for feature, supported in value.items(): self.assertEqual(cap["features"].get(feature), supported)
            else:
                self.assertEqual(cap[key],value)
        self.assertFalse(cap['auth']['apiKeyRequired'])
        self.assertEqual(cap['gatewayVersion'],'14.2.0')
        self.assertEqual(cap['memory']['authority'],'client')
        self.assertFalse(cap['features']['mcpFacade'])
        self.assertEqual(cap['contracts']['toolArguments'], ['gptmobile.tool-arguments.v1'])
        self.assertTrue(cap['features']['validatedToolArguments'])
        self.assertEqual(self.client.post('/mcp',json={}).status_code,404)
        self.assertFalse(self.gateway.runtime.AUTO_MEMORY_STORE)

    def test_keyfree_chat_calls_retained_runtime_without_dummy_key(self):
        from fastapi.responses import JSONResponse
        async def completion(request):
            body=await request.json()
            self.assertEqual(current_device.get(),'local:single-user')
            self.assertEqual(body['max_tokens'],20000)
            return JSONResponse({'choices':[{'message':{'content':'hello'}}]})
        with patch.object(self.gateway.runtime,'chat_completions',side_effect=completion):
            response=self.client.post('/v1/chat/completions',json={'model':'local','messages':[{'role':'user','content':'hi'}],'max_tokens':20000})
        self.assertEqual(response.status_code,200)

    def test_late_failure_and_cancel_cannot_overwrite_completion(self):
        r=self.gateway.runtime
        r.register_gateway_job('done','local','chat',threading.Event(),threading.Event())
        self.store.claim('done','local:single-user')
        result={'choices':[{'message':{'content':'done'}}]}
        r.finish_gateway_job('done',200,result)
        r.finish_gateway_job('done',500,None,'late disconnect')
        r.update_gateway_job('done',{'status':'failed','message':'late'})
        self.assertEqual(r.job_registry['done']['status'],'completed')
        self.assertEqual(r.job_registry['done']['_result'],result)
        self.assertEqual(self.client.post('/gateway/jobs/done/cancel').json()['status'],'completed')

    def test_distribution_detects_missing_companion(self):
        root=Path(__file__).resolve().parents[1]
        self.assertEqual(validate_package(root)['version'],'14.2.0')
        with tempfile.TemporaryDirectory() as target:
            shutil.copytree(root,target,dirs_exist_ok=True)
            (Path(target)/'gateway_v13_runtime.py').unlink()
            with self.assertRaises(ValueError): validate_package(target)



class ExactToolTests(unittest.TestCase):
    def runtime(self, definitions):
        from types import SimpleNamespace
        from unittest.mock import Mock
        return SimpleNamespace(get_all_mcp_tools=lambda refresh=False: definitions,
            load_mcp_config=lambda: {'a':{},'a_b':{}}, mcp_tool_metadata={'a_b_read':{'server':'a','tool':'b_read'}},
            mcp_cache_lock=threading.RLock(), mcp_tools_cache=definitions, mcp_call=Mock(return_value={'ok':True}))

    def test_exact_metadata_beats_longest_prefix_guess(self):
        from v14.tools import install_exact_routing
        runtime=self.runtime([{'function':{'name':'a_b_read','parameters':{'type':'object','properties':{},'required':[]}}}])
        install_exact_routing(runtime)
        runtime._execute_mcp_tool_single('a_b_read',{})
        self.assertEqual(runtime.mcp_call.call_args.args[0],'a')
        self.assertEqual(runtime.mcp_call.call_args.args[2]['name'],'b_read')

    def test_duplicate_alias_is_quarantined_without_dispatch(self):
        from v14.tools import install_exact_routing, CatalogBindingError
        tool={'function':{'name':'a_b_read','parameters':{'type':'object'}}}
        runtime=self.runtime([tool,tool])
        install_exact_routing(runtime)
        self.assertEqual(runtime.get_all_mcp_tools(),[])
        with self.assertRaises(CatalogBindingError): runtime._execute_mcp_tool_single('a_b_read',{})
        runtime.mcp_call.assert_not_called()

    def test_malformed_catalog_entries_do_not_hide_healthy_tools(self):
        from v14.tools import install_exact_routing
        runtime=self.runtime([{'function': None}, {'function': {'name': []}},
                              {'function': {'name':'bad', 'parameters': {'required': [[]]}}},
                              {'function': {'name':'a_b_read'}}])
        install_exact_routing(runtime)
        self.assertEqual(len(runtime.get_all_mcp_tools()), 1)
        runtime._execute_mcp_tool_single('a_b_read', {})
        runtime.mcp_call.assert_called_once()


class DurableFailureTests(unittest.TestCase):
    def test_failed_commit_does_not_leave_a_false_completed_job(self):
        from types import SimpleNamespace
        from v14.jobs import install_terminal_guard, DurableCommitError
        job={"status":"running","_segment_results":deque(maxlen=16)}
        def finish(job_id,status,result,error=None):
            job["status"]="completed"
            runtime.durable_persist_job(job_id)
        runtime=SimpleNamespace(job_registry={'j':job},job_registry_lock=threading.RLock(),
            finish_gateway_job=finish,update_gateway_job=lambda *a,**k: None,register_gateway_job=lambda *a,**k: None,
            durable_job_lock=threading.RLock(),_durable_connect=lambda:None,_durable_metric=lambda *a:None,
            GATEWAY_DURABLE_JOBS=True,durable_job_initialized=False,init_durable_job_store=lambda:False)
        install_terminal_guard(runtime)
        with self.assertRaises(DurableCommitError): runtime.finish_gateway_job('j',200,{'content':'done'})
        self.assertEqual(job['status'],'running')


if __name__ == '__main__': unittest.main()
