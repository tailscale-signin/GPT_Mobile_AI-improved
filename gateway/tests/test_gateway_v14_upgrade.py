"""Regression tests for admitted schemas, bounded discovery and read/action separation."""
import json
import sqlite3
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.actions import ActionLedger, ActionConflict, ExternalOutcomeUnknown, current_gateway_job_id, logical_action_id
from v14.catalog import CatalogCache
from v14.contracts import SchemaValidationError, compile_schema, model_schema, validate_arguments
from v14.operations import IMMUTABLE_READ, MUTABLE_READ, MUTATION, operation_class, select_equivalent
from v14.results import ToolViewCapacity, bound_result
from v14.tools import CatalogBindingError, install_exact_routing
from gateway_security import current_device


class GatewayUpgradeTests(unittest.TestCase):
    def test_annotations_local_refs_and_nullable_keep_full_execution_contract(self):
        schema = {'title':'large annotation '*100, '$schema':'https://json-schema.org/draft/2020-12/schema',
                  '$defs': {'path': {'type':'string', 'maxLength':5, 'description':'description '*100}},
                  'type':'object', 'additionalProperties':False, 'required':['path'],
                  'properties':{'path':{'$ref':'#/$defs/path'}, 'line':{'type':'integer','minimum':1,'nullable':True}}}
        compiled=compile_schema(schema);display=model_schema(schema)
        self.assertLess(len(json.dumps(display)),len(json.dumps(compiled)))
        validate_arguments(compiled,{'path':'Foo','line':None})
        for arguments in ({'path':'too-long'},{'path':'foo','line':False},{'path':'foo','line':0},{'path':'foo','extra':1}):
            with self.subTest(arguments=arguments),self.assertRaises(SchemaValidationError): validate_arguments(compiled,arguments)
        with self.assertRaises(SchemaValidationError): compile_schema({'type':'string','nullable':True,'pattern':'(a+)+$'})
        with self.assertRaises(SchemaValidationError): compile_schema({'$defs':{'loop':{'$ref':'#/$defs/loop'}},'$ref':'#/$defs/loop'})

    def test_unknown_assertions_reject_and_const_keeps_json_boolean_distinct(self):
        with self.assertRaises(SchemaValidationError): compile_schema({'type':'object','unevaluatedProperties':False})
        schema={'type':'object','properties':{'value':{'const':1}},'required':['value']}
        validate_arguments(schema,{'value':1})
        with self.assertRaises(SchemaValidationError): validate_arguments(schema,{'value':True})

    def test_read_classification_is_case_sensitive_and_never_trusts_remote_hints(self):
        self.assertEqual(IMMUTABLE_READ,operation_class('get_file_contents',{'ref':'a'*40,'path':'A.kt'}))
        self.assertEqual(MUTABLE_READ,operation_class('get_file_contents',{'ref':'main'}))
        self.assertEqual(MUTATION,operation_class('push_files',{'readOnlyHint':True}))
        self.assertEqual(MUTATION,operation_class('Get_File_Contents',{}))
        self.assertNotEqual(logical_action_id('j',{}, {'path':'A.kt'}),logical_action_id('j',{}, {'path':'a.kt'}))

    def test_read_timeout_does_not_poison_job_and_uncertain_write_allows_reconciliation_only(self):
        with tempfile.TemporaryDirectory() as directory:
            runtime=SimpleNamespace(GATEWAY_DURABLE_JOBS=True,durable_job_initialized=True,durable_job_lock=threading.RLock(),_durable_connect=lambda:sqlite3.connect(Path(directory)/'ledger.sqlite'))
            ledger=ActionLedger(runtime);binding={'server':'github','tool':'get_file_contents'}
            ledger.begin('owner','job','read',binding,{},operation_kind=MUTABLE_READ)
            ledger.uncertain('owner','job','read')
            self.assertIsNone(ledger.begin('owner','job','read',binding,{},operation_kind=MUTABLE_READ))
            ledger.finish('owner','job','read',{'head':'a'*40})
            ledger.begin('owner','job','write',{'tool':'push_files'}, {},operation_kind=MUTATION)
            ledger.uncertain('owner','job','write')
            self.assertIsNone(ledger.begin('owner','job','reconcile',binding,{},operation_kind=MUTABLE_READ))
            with self.assertRaises(ExternalOutcomeUnknown): ledger.begin('owner','job','next-write',{'tool':'push_files'}, {},operation_kind=MUTATION)
            with self.assertRaises(ActionConflict): ledger.begin('owner','job','write',{'tool':'push_files'}, {},operation_kind=MUTABLE_READ)
            with runtime._durable_connect() as db:
                self.assertEqual(2,db.execute("SELECT COUNT(*) FROM gateway_action_attempts WHERE action_id='read'").fetchone()[0])

    def test_final_assembled_result_cap_preserves_exact_cursors_after_pr_preload(self):
        cursor='opaque/+AbC=='
        assembled=json.dumps({'files':['large '*1000],'nextCursor':cursor})+'\nPR preload '+json.dumps({'head_sha':'a'*40,'details':'large '*1000})
        result=bound_result(assembled,900);receipt=json.loads(result)
        self.assertLessEqual(len(result),900)
        self.assertFalse(receipt['complete'])
        self.assertIn({'key':'nextCursor','value':cursor},receipt['continuations'])
        self.assertIn({'key':'sha','value':'a'*40},receipt['identities'])
        with self.assertRaises(ToolViewCapacity): bound_result(assembled,20)

    def test_discovery_runs_connections_concurrently_and_reuses_cache(self):
        calls=[];barrier=threading.Barrier(3)
        def discover(name,method,params,**kwargs):
            calls.append(name);barrier.wait(timeout=1)
            return {'tools':[{'name':'get_file_contents','inputSchema':{'type':'object'}}]}
        config={name:{} for name in ('one','two','three')}
        runtime=SimpleNamespace(load_mcp_config=lambda:config,mcp_call=discover,mcp_tool_metadata={})
        cache=CatalogCache(runtime,interactive=1,total=2)
        self.assertEqual(3,len(cache.get()))
        self.assertEqual(3,len(cache.get()))
        self.assertEqual(3,len(calls))
        self.assertTrue(all(row['connectionHealthy'] for row in cache.health.values()))

    def test_interactive_timeout_keeps_one_producer_and_late_revision_cannot_publish(self):
        gate=threading.Event();started=threading.Event();calls=[]
        config={'one':{'token':'first'}}
        def discover(*args,**kwargs):
            calls.append(args);started.set();gate.wait(timeout=1)
            return {'tools':[{'name':'read','inputSchema':{'type':'object'}}]}
        runtime=SimpleNamespace(load_mcp_config=lambda:dict(config),mcp_call=discover,mcp_tool_metadata={})
        cache=CatalogCache(runtime,interactive=.01,total=.1)
        self.assertEqual([],cache.get());self.assertTrue(started.wait(1));self.assertEqual([],cache.get())
        self.assertEqual(1,len(calls))
        config['one']={'token':'second'}
        gate.set()
        for event,_ in list(cache.flights.values()):event.wait(1)
        self.assertFalse(cache.cache)

    def test_deadline_does_not_spawn_duplicate_ignored_cancellation(self):
        gate=threading.Event();calls=[]
        def discover(*args,**kwargs):
            calls.append(args);gate.wait(timeout=1);return {'tools':[]}
        runtime=SimpleNamespace(load_mcp_config=lambda:{'one':{}},mcp_call=discover,mcp_tool_metadata={})
        cache=CatalogCache(runtime,interactive=.01,total=.03)
        cache.get();event=next(iter(cache.flights.values()))[0];self.assertTrue(event.wait(1))
        cache.get(force=True)
        self.assertEqual(1,len(calls));self.assertEqual('DISCOVERY_DEADLINE',next(iter(cache.health.values()))['reason'])
        gate.set()

    def test_routing_deduplicates_only_same_operation_and_authorization(self):
        definitions=[{'function':{'name':name}} for name in ('native','mcp','other')]
        metadata={'native':{'equivalentOperation':'read','authorizationScope':'one'},'mcp':{'equivalentOperation':'read','authorizationScope':'one'},'other':{'equivalentOperation':'read','authorizationScope':'two'}}
        self.assertEqual(['native','other'],[item['function']['name'] for item in select_equivalent(definitions,metadata)])

    def test_schema_cache_rejection_and_mutable_observations_are_revision_bound(self):
        with tempfile.TemporaryDirectory() as directory:
            schema={'type':'object','properties':{'path':{'type':'string','maxLength':10,'description':'long '*100}},'required':['path'],'title':'annotation '*100}
            config={'github':{'url':'https://example.test/mcp'}};calls=[]
            metadata={'github_get_file_contents':{'server':'github','tool':'get_file_contents','inputSchema':schema}}
            runtime=SimpleNamespace(GATEWAY_DURABLE_JOBS=True,durable_job_initialized=True,durable_job_lock=threading.RLock(),_durable_connect=lambda:sqlite3.connect(Path(directory)/'ledger.sqlite'),mcp_cache_lock=threading.RLock(),mcp_tool_metadata=metadata,load_mcp_config=lambda:config,get_all_mcp_tools=lambda force=False:[{'type':'function','function':{'name':'github_get_file_contents','parameters':schema}}],mcp_call=lambda *args,**kwargs:calls.append(args) or {'observed':len(calls)})
            install_exact_routing(runtime)
            self.assertNotIn('title',runtime.get_all_mcp_tools()[0]['function']['parameters'])
            runtime.get_all_mcp_tools();self.assertEqual(1,runtime.gateway_schema_metrics['compiled'])
            owner=current_device.set('owner');job=current_gateway_job_id.set('job')
            try:
                self.assertEqual({'observed':1},runtime._execute_mcp_tool_single('github_get_file_contents',{'path':'A.kt'},call_id='one'))
                self.assertEqual({'observed':2},runtime._execute_mcp_tool_single('github_get_file_contents',{'path':'A.kt'},call_id='two'))
                self.assertEqual({'observed':2},runtime._execute_mcp_tool_single('github_get_file_contents',{'path':'A.kt'},call_id='two'))
                with self.assertRaises(CatalogBindingError):runtime._execute_mcp_tool_single('github_get_file_contents',{'path':'too-long-path'},call_id='bad')
                self.assertEqual(2,len(calls))
            finally:current_gateway_job_id.reset(job);current_device.reset(owner)
            schema['unevaluatedProperties']=False
            self.assertEqual([],runtime.get_all_mcp_tools());self.assertIn('github_get_file_contents',runtime.gateway_schema_rejections)
            rejected=runtime.gateway_schema_metrics['rejected'];runtime.get_all_mcp_tools();self.assertEqual(rejected,runtime.gateway_schema_metrics['rejected'])

if __name__=='__main__':unittest.main()
