"""Revision-fenced, concurrent discovery with a bounded wait and detached producer."""
import hashlib
import json
import threading
import time

class CatalogCache:
    def __init__(self,runtime,interactive=2.0,total=8.0,ttl=300.0):
        self.runtime=runtime;self.interactive=interactive;self.total=total;self.ttl=ttl
        self.lock=threading.RLock();self.cache={};self.flights={};self.health={}
    @staticmethod
    def key(name,config):
        return name+':'+hashlib.sha256(json.dumps(config,sort_keys=True,separators=(',',':'),default=str).encode()).hexdigest()
    def get(self,force=False):
        config=self.runtime.load_mcp_config();keys={name:self.key(name,cfg) for name,cfg in config.items()}
        if len(keys)>64: raise ValueError('MCP connection catalogue exceeds 64 connections')
        waits=[]
        with self.lock:
            for key in list(self.cache):
                if key not in keys.values(): self.cache.pop(key,None);self.health.pop(key,None)
            for name,key in keys.items():
                cached=self.cache.get(key);health=self.health.get(key,{})
                if force or not cached or time.monotonic()-cached[0]>self.ttl:
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
                if cached and self.health.get(key,{}).get('authorized',True):
                    result.extend(cached[1]);self.runtime.mcp_tool_metadata.update(cached[2])
        return sorted(result,key=lambda item:item['function']['name'])
    def _load(self,name,key,cfg,event,cancel):
        started=time.monotonic();end=started+self.total
        def expired():
            cancel.set()
            with self.lock:
                if self.flights.get(key,(None,None))[0] is event:
                    self.health[key]={'authorized':True,'schemaKnown':key in self.cache,'connectionHealthy':False,'executionAvailable':False,'reason':'DISCOVERY_DEADLINE','retryAt':time.monotonic()+30}
                    # Keep physical ownership until the producer exits, even if transport ignores cancellation.
                    # The completed event lets callers leave without starting duplicate hidden requests.
            event.set()
        timer=threading.Timer(self.total,expired);timer.daemon=True;timer.start()
        try:
            rows=[];cursor=None;seen=set();size=0
            for page in range(32):
                if cancel.is_set() or time.monotonic()>=end: raise TimeoutError('Discovery total deadline')
                response=self.runtime.mcp_call(name,'tools/list',{'cursor':cursor} if cursor else {},cancel_event=cancel)
                size+=len(json.dumps(response,ensure_ascii=False).encode())
                if size>4*1024*1024: raise ValueError('Catalogue byte limit')
                rows.extend(response.get('tools',[]))
                if len(rows)>2048: raise ValueError('Catalogue tool limit')
                cursor=response.get('nextCursor')
                if not cursor: break
                if not isinstance(cursor,str) or len(cursor)>4096 or cursor in seen: raise ValueError('Invalid/repeated catalogue cursor')
                seen.add(cursor)
            else: raise ValueError('Catalogue page limit')
            definitions=[];metadata={};include=set(cfg.get('includeTools') or []);exclude=set(cfg.get('excludeTools') or [])
            for row in rows:
                tool=row.get('name')
                if not isinstance(tool,str) or tool in exclude or include and tool not in include: continue
                full=name+'_'+tool;schema=row.get('inputSchema',{'type':'object','properties':{}})
                metadata[full]={'server':name,'tool':tool,'inputSchema':schema,'annotations':row.get('annotations')}
                definitions.append({'type':'function','function':{'name':full,'description':str(row.get('description','')),'parameters':schema}})
            if len({d['function']['name'] for d in definitions})!=len(definitions): raise ValueError('Duplicate catalogue tool')
            with self.lock:
                current=self.runtime.load_mcp_config().get(name)
                if cancel.is_set() or current is None or self.key(name,current)!=key: return
                self.cache[key]=(time.monotonic(),definitions,metadata)
                self.health[key]={'authorized':True,'schemaKnown':True,'connectionHealthy':True,'executionAvailable':True,'retryAt':0}
        except Exception as error:
            with self.lock:
                if not cancel.is_set():
                    unauthorized=any(code in str(error) for code in ('401','403','Unauthorized'))
                    self.health[key]={'authorized':not unauthorized,'schemaKnown':key in self.cache,'connectionHealthy':False,'executionAvailable':False,'reason':type(error).__name__,'retryAt':float('inf') if unauthorized else time.monotonic()+30}
        finally:
            timer.cancel()
            with self.lock:
                if self.flights.get(key,(None,None))[0] is event: self.flights.pop(key,None)
            event.set()

def install_catalog_cache(runtime):
    cache=CatalogCache(runtime);runtime.gateway_catalogue=cache;runtime.get_all_mcp_tools=cache.get
