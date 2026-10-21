"""One cap after complete result assembly, with explicit structured incompleteness."""
import hashlib
import json
import re

class ToolViewCapacity(ValueError):
    pass

def bound_result(value,maximum):
    text=value if isinstance(value,str) else json.dumps(value,ensure_ascii=False,separators=(',',':'))
    if maximum<=0 or len(text)<=maximum: return text
    continuations=[];identities=[]
    def retain(node):
        if isinstance(node,dict):
            for key,item in node.items():
                if key in {'nextCursor','next_cursor','cursor','endCursor','view_cursor','continuation'} and isinstance(item,str) and item:
                    if len(item)>4096: raise ToolViewCapacity('TOOL_VIEW_CAPACITY: continuation exceeds safe receipt limit')
                    continuations.append({'key':key,'value':item})
                if key.lower() in {'sha','head_sha','base_sha','blob_sha','tree_sha','commit_sha'} and isinstance(item,str): identities.append({'key':key,'value':item})
                retain(item)
        elif isinstance(node,list):
            for item in node: retain(item)
    try: retain(json.loads(text))
    except json.JSONDecodeError:
        for match in re.finditer(r'"(nextCursor|next_cursor|endCursor|view_cursor|continuation)"\s*:\s*("(?:[^"\\]|\\.)*")',text):
            retain({match.group(1):json.loads(match.group(2))})
        identities.extend({'key':'sha','value':match.group()} for match in re.finditer(r'\b[0-9a-fA-F]{40}(?:[0-9a-fA-F]{24})?\b',text))
    envelope={'schema':'gateway.bounded-result.v1','complete':False,'truncated':True,
              'source_sha256':hashlib.sha256(text.encode()).hexdigest(),'source_characters':len(text),
              'continuations':list({(x['key'],x['value']):x for x in continuations}.values()),
              'identities':list({(x['key'],x['value']):x for x in identities}.values()),
              'coverage':'partial model view; full result retained in the turn checkpoint','excerpt':''}
    def render():return json.dumps(envelope,ensure_ascii=False,separators=(',',':'))
    if len(render())>maximum: raise ToolViewCapacity('TOOL_VIEW_CAPACITY: exact continuation receipt cannot fit')
    lo,hi=0,len(text)
    while lo<hi:
        mid=(lo+hi+1)//2;envelope['excerpt']=text[:mid]
        if len(render())<=maximum:lo=mid
        else:hi=mid-1
    envelope['excerpt']=text[:lo]
    return render()
