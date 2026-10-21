"""Bounded JSON Schema admission. Presentation never replaces execution validation."""
from __future__ import annotations
import copy
import json
import math
import re

class SchemaValidationError(ValueError):
    pass

_TYPES = {'object', 'array', 'string', 'number', 'integer', 'boolean', 'null'}
_ANNOTATIONS = {'title','description','default','examples','$schema','$id','$comment','deprecated','readOnly','writeOnly','format','contentEncoding','contentMediaType'}
_ASSERTIONS = {'type','properties','required','additionalProperties','items','enum','const','minLength','maxLength','pattern','minimum','maximum','exclusiveMinimum','exclusiveMaximum','multipleOf','minItems','maxItems','uniqueItems','minProperties','maxProperties','allOf','anyOf','oneOf','not'}

def _equal(a,b):
    if isinstance(a,bool) != isinstance(b,bool): return False
    if isinstance(a,dict) and isinstance(b,dict): return a.keys()==b.keys() and all(_equal(a[k],b[k]) for k in a)
    if isinstance(a,list) and isinstance(b,list): return len(a)==len(b) and all(_equal(x,y) for x,y in zip(a,b))
    return a==b

def compile_schema(schema):
    """Resolve only bounded local acyclic references; reject unknown assertions explicitly."""
    if not isinstance(schema,(dict,bool)): raise SchemaValidationError('Tool schema must be an object')
    root=copy.deepcopy(schema); nodes=[0]
    def visit(spec,depth=0,refs=()):
        nodes[0]+=1
        if depth>16 or nodes[0]>2000: raise SchemaValidationError('Schema expansion exceeds the bound')
        if isinstance(spec,bool): return spec
        if not isinstance(spec,dict): raise SchemaValidationError('Schema node must be object or boolean')
        unknown=set(spec)-_ANNOTATIONS-_ASSERTIONS-{'$ref','$defs','definitions','nullable'}
        if unknown: raise SchemaValidationError('Unsupported schema assertion: '+','.join(sorted(unknown)))
        if '$ref' in spec:
            ref=spec['$ref']
            if not isinstance(ref,str) or not ref.startswith('#/') or ref in refs: raise SchemaValidationError('Only acyclic local schema references are supported')
            target=root
            try:
                for part in ref[2:].split('/'): target=target[part.replace('~1','/').replace('~0','~')]
            except (KeyError,TypeError): raise SchemaValidationError('Unresolved local schema reference') from None
            resolved=visit(target,depth+1,refs+(ref,))
            siblings={k:v for k,v in spec.items() if k not in {'$ref','$defs','definitions'}}
            return {'allOf':[resolved,visit(siblings,depth+1,refs)]} if siblings else resolved
        out={k:copy.deepcopy(v) for k,v in spec.items() if k in _ANNOTATIONS}
        for key,value in spec.items():
            if key in {'properties'}:
                if not isinstance(value,dict) or len(value)>100 or any(not isinstance(k,str) or not 1<=len(k)<=128 for k in value): raise SchemaValidationError('Invalid object properties')
                out[key]={k:visit(v,depth+1,refs) for k,v in value.items()}
            elif key in {'items','additionalProperties','not'}: out[key]=visit(value,depth+1,refs)
            elif key in {'allOf','anyOf','oneOf'}:
                if not isinstance(value,list) or not 1<=len(value)<=32: raise SchemaValidationError('Invalid schema composition')
                out[key]=[visit(v,depth+1,refs) for v in value]
            elif key in _ASSERTIONS: out[key]=copy.deepcopy(value)
        kind=out.get('type')
        if kind is not None:
            kinds=kind if isinstance(kind,list) else [kind]
            if not kinds or any(not isinstance(k,str) or k not in _TYPES for k in kinds) or len(set(kinds))!=len(kinds): raise SchemaValidationError('Invalid schema type')
        if 'nullable' in spec:
            if not isinstance(spec['nullable'],bool): raise SchemaValidationError('nullable must be boolean')
            if spec['nullable']:
                out={'anyOf':[out,{'type':'null'}]}
                return out
        if 'required' in out and (not isinstance(out['required'],list) or len(out['required'])>100 or any(not isinstance(k,str) for k in out['required']) or len(set(out['required']))!=len(out['required'])): raise SchemaValidationError('Invalid required properties')
        if 'enum' in out and (not isinstance(out['enum'],list) or not 1<=len(out['enum'])<=100): raise SchemaValidationError('Invalid enum')
        for key in ('minLength','maxLength','minItems','maxItems','minProperties','maxProperties'):
            v=out.get(key)
            if v is not None and (isinstance(v,bool) or not isinstance(v,int) or not 0<=v<=100000): raise SchemaValidationError('Invalid bound '+key)
        for key in ('minimum','maximum','exclusiveMinimum','exclusiveMaximum','multipleOf'):
            v=out.get(key)
            if v is not None and (isinstance(v,bool) or not isinstance(v,(int,float)) or not math.isfinite(v) or key=='multipleOf' and v<=0): raise SchemaValidationError('Invalid numeric bound')
        if 'uniqueItems' in out and not isinstance(out['uniqueItems'],bool): raise SchemaValidationError('uniqueItems must be boolean')
        for lo,hi in [('minLength','maxLength'),('minItems','maxItems'),('minProperties','maxProperties'),('minimum','maximum')]:
            if lo in out and hi in out and out[lo]>out[hi]: raise SchemaValidationError('Inverted schema range')
        pattern=out.get('pattern')
        if pattern is not None:
            if not isinstance(pattern,str) or len(pattern)>256 or re.search(r'[()|\\]|\{\d',pattern) or re.search(r'(?:\*|\+|\?){2}',pattern): raise SchemaValidationError('Pattern exceeds the safe subset')
            try: re.compile(pattern)
            except re.error as exc: raise SchemaValidationError('Invalid pattern') from exc
        return out
    try: json.dumps(root,allow_nan=False)
    except (ValueError,TypeError) as exc: raise SchemaValidationError('Schema is not finite JSON') from exc
    return visit(root)

def validate_schema(schema,*,depth=0):
    compile_schema(schema)

def model_schema(schema):
    """Compact annotations only. The separate full contract remains authoritative."""
    compiled=compile_schema(schema)
    def compact(value):
        if isinstance(value,bool): return value
        result={}
        for key,item in value.items():
            if key=='description': result[key]=str(item)[:160]
            elif key in _ANNOTATIONS: continue
            elif key=='properties': result[key]={k:compact(v) for k,v in item.items()}
            elif key in {'items','additionalProperties','not'}: result[key]=compact(item)
            elif key in {'allOf','oneOf','anyOf'}: result[key]=[compact(v) for v in item]
            else: result[key]=item
        return result
    return compact(compiled)

def validate_arguments(schema,arguments,*,max_nodes=2000):
    spec=compile_schema(schema)
    if not isinstance(arguments,dict): raise SchemaValidationError('Tool arguments must be a JSON object')
    budget=[max_nodes]
    def bounded(value,depth=0):
        budget[0]-=1
        if budget[0]<0 or depth>16: raise SchemaValidationError('Tool arguments exceed validation limit')
        if isinstance(value,dict):
            if len(value)>100 or any(not isinstance(k,str) or len(k)>128 for k in value): raise SchemaValidationError('Too many or invalid properties')
            for child in value.values(): bounded(child,depth+1)
        elif isinstance(value,list):
            if len(value)>10000: raise SchemaValidationError('Too many items')
            for child in value: bounded(child,depth+1)
        elif isinstance(value,str):
            if len(value)>100000: raise SchemaValidationError('String too large')
        elif value is not None and not isinstance(value,(bool,int,float)) or isinstance(value,float) and not math.isfinite(value): raise SchemaValidationError('Non-JSON value')
    bounded(arguments)
    def matches(s,v,depth=0):
        budget[0]-=1
        if budget[0]<0 or depth>32: raise SchemaValidationError('Schema execution exceeds validation limit')
        if isinstance(s,bool): return s
        kind=s.get('type');kinds=kind if isinstance(kind,list) else [kind]
        types={'object':isinstance(v,dict),'array':isinstance(v,list),'string':isinstance(v,str),'boolean':isinstance(v,bool),'null':v is None,'integer':isinstance(v,int) and not isinstance(v,bool),'number':isinstance(v,(int,float)) and not isinstance(v,bool)}
        if kind is not None and not any(types[k] for k in kinds): return False
        if 'const' in s and not _equal(s['const'],v) or 'enum' in s and not any(_equal(x,v) for x in s['enum']): return False
        for key in ('allOf','anyOf','oneOf'):
            if key in s:
                count=sum(matches(child,v,depth+1) for child in s[key])
                if key=='allOf' and count!=len(s[key]) or key=='anyOf' and count==0 or key=='oneOf' and count!=1: return False
        if 'not' in s and matches(s['not'],v,depth+1): return False
        if isinstance(v,dict):
            if not set(s.get('required',[]))<=v.keys() or not s.get('minProperties',0)<=len(v)<=s.get('maxProperties',100000): return False
            props=s.get('properties',{})
            for key,value in v.items():
                if key in props:
                    if not matches(props[key],value,depth+1): return False
                elif not matches(s.get('additionalProperties',True),value,depth+1): return False
        elif isinstance(v,list):
            if not s.get('minItems',0)<=len(v)<=s.get('maxItems',10000): return False
            if s.get('uniqueItems') and any(_equal(v[i],v[j]) for i in range(len(v)) for j in range(i)): return False
            if 'items' in s and not all(matches(s['items'],x,depth+1) for x in v): return False
        elif isinstance(v,str):
            if not s.get('minLength',0)<=len(v)<=s.get('maxLength',100000) or 'pattern' in s and re.search(s['pattern'],v) is None: return False
        elif isinstance(v,(int,float)) and not isinstance(v,bool):
            if 'minimum' in s and v<s['minimum'] or 'maximum' in s and v>s['maximum'] or 'exclusiveMinimum' in s and v<=s['exclusiveMinimum'] or 'exclusiveMaximum' in s and v>=s['exclusiveMaximum']: return False
            if 'multipleOf' in s and not math.isclose(v/s['multipleOf'],round(v/s['multipleOf']),rel_tol=1e-12,abs_tol=1e-12): return False
        return True
    if not matches(spec,arguments): raise SchemaValidationError('Arguments do not satisfy the admitted execution schema')
