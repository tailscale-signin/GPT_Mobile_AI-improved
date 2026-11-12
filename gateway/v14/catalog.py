"""Revision-fenced, concurrent discovery with a bounded wait and detached producer."""
import hashlib
import copy
import json
import threading
import time
import re
from v14.contracts import validate_schema

class CatalogCache:
    def __init__(self,runtime,interactive=2.0,total=8.0,ttl=300.0):
        self.runtime=runtime;self.interactive=interactive;self.total=total;self.ttl=ttl
        self.lock=threading.RLock();self.cache={};self.flights={};self.health={}
    @staticmethod
    def key(name,config):
        return name+':'+hashlib.sha256(json.dumps(config,sort_keys=True,separators=(',',':'),default=str).encode()).hexdigest()
    def readiness(self):
        """Read current evidence without starting discovery, processes, or a tool call."""
        config = self.runtime.load_mcp_config()
        keys = [self.key(name, item) for name, item in config.items()]
        with self.lock:
            if not keys:
                return {'toolsReady': None, 'toolsState': 'NOT_CONFIGURED', 'toolConnectionCount': 0,
                        'toolCount': 0, 'schemaReady': None, 'executionVerified': None}
            snapshots = [(self.cache.get(key), self.health.get(key, {})) for key in keys]
            count = sum(len(cached[1]) for cached, _ in snapshots if cached)
            schema_ready = all(cached and time.monotonic() - cached[0] <= self.ttl and health.get('connectionHealthy') is True and health.get('authorized') is not False and not health.get('catalogRejected',False) for cached, health in snapshots)
            if any(health.get('authorized') is False for _, health in snapshots):
                ready, state = False, 'AUTH_REQUIRED'
            elif any(health.get('executionAvailable') is False for _, health in snapshots):
                ready, state = False, 'UNAVAILABLE'
            elif any(not cached for cached, _ in snapshots):
                ready, state = None, 'NOT_DISCOVERED'
            elif any(time.monotonic() - cached[0] > self.ttl for cached, _ in snapshots):
                ready, state = False, 'STALE'
            elif schema_ready and all(health.get('executionAvailable') is True for _, health in snapshots):
                ready, state = True, 'READY'
            else:
                ready, state = None, 'NOT_MEASURED'
            observed = [health.get('executionVerified') for _, health in snapshots]
            execution = False if False in observed else True if all(value is True for value in observed) else None
            return {'toolsReady': ready, 'toolsState': state, 'toolConnectionCount': len(keys),
                    'toolCount': count, 'schemaReady': schema_ready, 'executionVerified': execution}

    def connection_observations(self):
        """Safe, passive connection evidence, without commands, addresses or credentials."""
        with self.lock:
            return self._connection_observations(copy.deepcopy(self.runtime.load_mcp_config()))

    def _connection_observations(self, config):
        now = time.monotonic()
        result = []
        with self.lock:
            for name, value in sorted(config.items()):
                key = self.key(name, value)
                cached, health = self.cache.get(key), self.health.get(key, {})
                fresh = cached is not None and now - cached[0] <= self.ttl
                schema_ready = bool(fresh and health.get('connectionHealthy') is True and
                                    health.get('authorized') is not False and not health.get('catalogRejected'))
                reason = health.get('reasonCode')
                if not value.get('enabled', True):
                    state, reason, action = 'DISABLED', 'MCP_CONNECTION_DISABLED', 'CONFIGURE_CONNECTION'
                    schema_ready = False
                elif health.get('authorized') is False:
                    state, reason, action = 'AUTH_REQUIRED', 'MCP_AUTH_REQUIRED', 'REAUTHORIZE_CONNECTION'
                elif health.get('catalogRejected'):
                    state, reason, action = 'INVALID_CATALOG', 'MCP_CATALOG_INVALID', 'FIX_TOOL_SCHEMA'
                elif health.get('connectionHealthy') is False:
                    state, action = 'UNAVAILABLE', 'CHECK_LISTENER_AND_RETRY_DISCOVERY'
                    reason = reason or 'MCP_CONNECTION_UNAVAILABLE'
                elif key in self.flights:
                    state, reason, action = 'DISCOVERING', 'MCP_DISCOVERY_IN_PROGRESS', 'WAIT_FOR_DISCOVERY'
                elif not cached:
                    state, reason, action = 'NOT_DISCOVERED', 'MCP_DISCOVERY_REQUIRED', 'DISCOVER_TOOLS'
                elif not fresh:
                    state, reason, action = 'STALE', 'MCP_CATALOG_STALE', 'REFRESH_DISCOVERY'
                elif not cached[1]:
                    state, reason, action = 'EMPTY_CATALOG', 'MCP_NO_TOOLS_ADVERTISED', 'CHECK_TOOL_FILTERS'
                elif schema_ready:
                    state, reason, action = 'READY_FOR_DISPATCH', None, None
                else:
                    state, reason, action = 'NOT_MEASURED', 'MCP_CONNECTION_NOT_MEASURED', 'REFRESH_DISCOVERY'
                retry_at = health.get('retryAt', 0)
                result.append({'serviceId': name, 'state': state, 'reasonCode': reason, 'recoveryAction': action,
                               'schemaReady': schema_ready, 'toolCount': len(cached[1]) if cached else 0,
                               'executionVerified': health.get('executionVerified'),
                               'executionObservedAtMs': health.get('executionObservedAt'),
                               'lastDiscoverySuccessAtMs': health.get('lastDiscoverySuccessAtMs'),
                               'catalogAgeMs': max(0, int((now - cached[0]) * 1000)) if cached else None,
                               'retryAfterMs': max(0, int((retry_at - now) * 1000)) if retry_at != float('inf') else None,
                               'retryRequiresConfigurationChange': retry_at == float('inf')})
        return result

    def task_readiness(self, required_tools):
        """Check only exact required tools; discovery never proves successful execution."""
        if len(required_tools) > 64 or any(not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', name) for name in required_tools):
            raise ValueError('At most 64 exact advertised tool names are accepted')
        tools = []
        with self.lock:
            config = copy.deepcopy(self.runtime.load_mcp_config())
            connections = {row['serviceId']: row for row in self._connection_observations(config)}
            for tool in dict.fromkeys(required_tools):
                matches = [(name, cached) for name, cfg in config.items()
                           if (cached := self.cache.get(self.key(name, cfg))) and tool in cached[2]]
                if len(matches) != 1:
                    tools.append({'tool': tool, 'ready': False, 'reasonCode': 'MCP_REQUIRED_TOOL_NOT_ADVERTISED',
                                  'recoveryAction': 'DISCOVER_EXACT_REQUIRED_TOOL', 'executionVerified': None})
                    continue
                name, cached = matches[0]
                observed = connections[name]
                health = self.health.get(self.key(name, config[name]), {})
                tools.append({'tool': tool, 'serviceId': name, 'ready': observed['schemaReady'],
                              'reasonCode': observed['reasonCode'], 'recoveryAction': observed['recoveryAction'],
                              'retryAfterMs': observed['retryAfterMs'],
                              'executionVerified': health.get('executionVerified') if health.get('executionTool') == cached[2][tool]['tool'] else None})
            current = self.runtime.load_mcp_config()
            for row in tools:
                name = row.get('serviceId')
                if name and (name not in current or self.key(name, current[name]) != self.key(name, config[name])):
                    row.update(ready=False, reasonCode='MCP_CONFIGURATION_CHANGED', recoveryAction='REFRESH_DISCOVERY', executionVerified=None)
        return {'ready': all(row['ready'] for row in tools), 'scope': 'REQUESTED_TOOLS_ONLY',
                'retryable': any(not row['ready'] and (row.get('retryAfterMs') or 0) > 0 for row in tools),
                'requiredTools': tools, 'executionCoverage': 'Exact observed operations only; schema readiness permits dispatch but does not prove execution'}

    def _discovery_failure(self, key, reason, *, unauthorized=False, invalid=False):
        previous = self.health.get(key, {})
        # A late timeout/transport failure cannot undo an observed authorization
        # or schema rejection. Only a successful discovery restores that contract.
        unauthorized = unauthorized or previous.get('authorized') is False
        invalid = invalid or previous.get('catalogRejected', False)
        reason = 'MCP_AUTH_REQUIRED' if unauthorized else 'MCP_CATALOG_INVALID' if invalid else reason
        failures = min(10, previous.get('consecutiveFailures', 0) + 1)
        self.health[key] = {**previous, 'authorized': not unauthorized, 'schemaKnown': key in self.cache,
                            'connectionHealthy': False, 'executionAvailable': False,
                            'reasonCode': reason, 'reason': reason.removeprefix('MCP_'), 'catalogRejected': invalid, 'consecutiveFailures': failures,
                            'retryAt': float('inf') if unauthorized or invalid else time.monotonic() + min(300, 30 * 2 ** (failures - 1))}

    def get(self,force=False):
        config=self.runtime.load_mcp_config();keys={name:self.key(name,cfg) for name,cfg in config.items()}
        if len(keys)>64: raise ValueError('MCP connection catalogue exceeds 64 connections')
        waits=[]
        with self.lock:
            for key, (_, cancel) in self.flights.items():
                if key not in keys.values():
                    cancel.set()  # Retain ownership until the superseded producer exits.
            for key in list(self.health):
                if key not in keys.values():
                    self.health.pop(key, None)
            for key in list(self.cache):
                if key not in keys.values(): self.cache.pop(key,None);self.health.pop(key,None)
            for name,key in keys.items():
                cached=self.cache.get(key);health=self.health.get(key,{})
                if force or not cached or time.monotonic()-cached[0]>self.ttl or health.get('connectionHealthy') is False:
                    if key not in self.flights and (force or time.monotonic()>=health.get('retryAt',0)):
                        event=threading.Event();cancel=threading.Event();self.flights[key]=(event,cancel)
                        threading.Thread(target=self._load,args=(name,key,config[name],event,cancel),daemon=True).start()
                    if not cached and key in self.flights: waits.append(self.flights[key][0])
        end=time.monotonic()+self.interactive
        for event in waits: event.wait(max(0,end-time.monotonic()))
        result=[]
        with self.lock:
            current=self.runtime.load_mcp_config()
            for name,key in keys.items():
                if name not in current or self.key(name,current[name])!=key: continue
                cached=self.cache.get(key)
                if cached and self.health.get(key,{}).get('authorized',True) and not self.health.get(key,{}).get('catalogRejected',False):
                    result.extend(cached[1]);self.runtime.mcp_tool_metadata.update(cached[2])
        return sorted(result,key=lambda item:item['function']['name'])
    def _load(self,name,key,cfg,event,cancel):
        started=time.monotonic();end=started+self.total
        def expired():
            cancel.set()
            with self.lock:
                current = self.runtime.load_mcp_config().get(name)
                if self.flights.get(key,(None,None))[0] is event and current is not None and self.key(name,current)==key:
                    self._discovery_failure(key, 'MCP_DISCOVERY_DEADLINE')
                    # Keep physical ownership until the producer exits, even if transport ignores cancellation.
                    # The completed event lets callers leave without starting duplicate hidden requests.
            event.set()
        timer=threading.Timer(self.total,expired);timer.daemon=True;timer.start()
        try:
            rows=[];cursor=None;seen=set();size=0
            for page in range(32):
                if cancel.is_set() or time.monotonic()>=end: raise TimeoutError('Discovery total deadline')
                response=self.runtime.mcp_call(name,'tools/list',{'cursor':cursor} if cursor else {},cancel_event=cancel)
                if not isinstance(response,dict) or response.get('isError') is True or not isinstance(response.get('tools'),list):
                    raise ValueError('Malformed MCP catalogue page')
                size+=len(json.dumps(response,ensure_ascii=False,allow_nan=False).encode())
                if size>4*1024*1024: raise ValueError('Catalogue byte limit')
                rows.extend(response.get('tools',[]))
                if len(rows)>2048: raise ValueError('Catalogue tool limit')
                cursor=response.get('nextCursor')
                if cursor is None or cursor == '': break
                if not isinstance(cursor,str) or len(cursor)>4096 or cursor in seen: raise ValueError('Invalid/repeated catalogue cursor')
                seen.add(cursor)
            else: raise ValueError('Catalogue page limit')
            definitions=[];metadata={};include=set(cfg.get('includeTools') or []);exclude=set(cfg.get('excludeTools') or [])
            for row in rows:
                if not isinstance(row,dict): raise ValueError('Malformed MCP tool definition')
                tool=row.get('name')
                if not isinstance(tool,str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', name+'_'+tool):
                    raise ValueError('Malformed MCP tool name')
                if tool in exclude or include and tool not in include: continue
                full=name+'_'+tool;schema=row.get('inputSchema')
                if not isinstance(schema,dict): raise ValueError('MCP input schema must be an object')
                validate_schema(schema)  # Readiness cannot claim an unvalidated catalogue.
                metadata[full]={'server':name,'tool':tool,'inputSchema':schema,'annotations':row.get('annotations')}
                definitions.append({'type':'function','function':{'name':full,'description':str(row.get('description','')),'parameters':schema}})
            if len({d['function']['name'] for d in definitions})!=len(definitions): raise ValueError('Duplicate catalogue tool')
            with self.lock:
                current=self.runtime.load_mcp_config().get(name)
                if cancel.is_set() or current is None or self.key(name,current)!=key: return
                old = self.cache.get(key)
                same_contract = old is not None and old[2] == metadata
                previous = self.health.get(key, {}) if same_contract else {}
                self.cache[key]=(time.monotonic(),definitions,metadata)
                self.health[key]={'authorized':True,'schemaKnown':True,'connectionHealthy':True,
                                  'executionAvailable': previous.get('executionAvailable'),
                                  'executionVerified': previous.get('executionVerified'),
                                  'executionObservedAt': previous.get('executionObservedAt'),
                                  'executionTool': previous.get('executionTool'), 'retryAt':0,
                                  'consecutiveFailures': 0, 'lastDiscoverySuccessAtMs': int(time.time() * 1000)}
        except Exception as error:
            with self.lock:
                current = self.runtime.load_mcp_config().get(name)
                if not cancel.is_set() and current is not None and self.key(name,current)==key:
                    status = getattr(getattr(error, 'response', None), 'status_code', getattr(error, 'status_code', None))
                    unauthorized = isinstance(error,PermissionError) or status in {401,403} or re.search(r'(?i)\b(?:HTTP(?: Error)?\s+|RPC:\s*)(?:401|403)\b|\bunauthori[sz]ed\b',str(error)) is not None
                    invalid = isinstance(error, (ValueError, TypeError))
                    reason = ('MCP_AUTH_REQUIRED' if unauthorized else 'MCP_CATALOG_INVALID' if invalid else
                              'MCP_CONNECTION_REFUSED' if getattr(error, 'errno', None) in {61, 111, 10061} or re.search(r'(?i)connection refused|actively refused|winerror 10061', str(error)) else
                              'MCP_DISCOVERY_DEADLINE' if isinstance(error, TimeoutError) or 'timeout' in type(error).__name__.lower() else 'MCP_CONNECTION_UNAVAILABLE')
                    self._discovery_failure(key, reason, unauthorized=unauthorized, invalid=invalid)
        finally:
            timer.cancel()
            with self.lock:
                if self.flights.get(key,(None,None))[0] is event: self.flights.pop(key,None)
            event.set()

    def execution_contract(self, name, config, tool):
        """Bind execution observations to the exact advertised tool before I/O."""
        key = self.key(name, config)
        with self.lock:
            cached, health = self.cache.get(key), self.health.get(key, {})
            if self.runtime.load_mcp_config().get(name) != config or not cached or health.get('authorized') is False or health.get('catalogRejected'):
                return None
            metadata = cached[2].get(name + '_' + str(tool))
            if metadata is None:
                return None
            return hashlib.sha256(json.dumps(metadata, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()).hexdigest()

    def observe_execution(self, name, config, tool, succeeded, *, contract=None):
        key = self.key(name, config)
        with self.lock:
            # No catalogue or a response from an old schema is not evidence
            # about the newly advertised contract. Authorization changes win.
            if contract is None or self.execution_contract(name, config, tool) != contract:
                return
            self.health.setdefault(key, {}).update(executionAvailable=bool(succeeded), executionVerified=bool(succeeded),
                                                    executionObservedAt=int(time.time() * 1000),
                                                    executionTool=str(tool or '')[:128])

def install_catalog_cache(runtime):
    cache=CatalogCache(runtime);runtime.gateway_catalogue=cache;runtime.get_all_mcp_tools=cache.get
