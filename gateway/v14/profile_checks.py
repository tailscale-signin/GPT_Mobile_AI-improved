"""Run an Android-selected model's approved check without calling a host-side model."""
import hashlib
import json
import re
import threading
import time
from urllib.parse import quote

from desktop.repository_validation import repository_name
from v14.engineering_backend import safe_path
from v14.engineering_crypto import EngineeringCipher
from v14.engineering_source import prepare_source
from v14.engineering_validation import load_policy, named_test_results, patch_identity, validate
from v14.execution_binding import fingerprint


class ProfileChecks:
    def __init__(self, service):
        self.service = service
        self.store = service.store
        self.boot = service.bindings.boot_id

    def admitted(self, owner, repository):
        if not self.store.tx(lambda db: self.store.admission_settings(db, owner)['enabled']):
            raise PermissionError('Enable engineering for this paired owner')
        policy, _, image, commands, _ = load_policy(repository)
        if policy.get('remote_profile_checks') is not True:
            raise PermissionError('Enable model-requested offline checks for this repository on the host')
        return hashlib.sha256(json.dumps(policy, sort_keys=True, separators=(',', ':')).encode()).hexdigest(), image, commands, (policy.get('prepared_environment') or {}).get('profile', 'Custom repository')

    @staticmethod
    def schema(db):
        db.execute('CREATE TABLE IF NOT EXISTS engineering_profile_checks (owner TEXT, request_id TEXT, fingerprint TEXT, boot TEXT, state TEXT, updated REAL, receipt TEXT, PRIMARY KEY(owner,request_id))')

    def execute(self, owner, args):
        if set(args) - {'action','repository','branch','head_sha','profile_binding','request_id','check_index','changes'}:
            raise ValueError('Unsupported profile workspace arguments')
        repo = repository_name(args.get('repository'))
        branch, head, binding = (args.get(k) for k in ('branch','head_sha','profile_binding'))
        if (not isinstance(branch, str) or not 1 <= len(branch) <= 240 or any(ord(c)<32 for c in branch)
                or not re.fullmatch('[0-9a-f]{40}', str(head)) or not re.fullmatch('[0-9a-f]{64}', str(binding))):
            raise ValueError('Specify the bound repository, branch, revision and profile')
        policy, image, commands, environment = self.admitted(owner, repo)
        common = dict(repository=repo, branch=branch, head_sha=head, profile_binding=binding, executor='gateway', image=image, policy_sha256=policy)
        backend = self.service.backend
        api = '/repos/' + repo
        ref = backend.api(api + '/git/ref/heads/' + quote(branch, safe=''), read_only=True)
        if ref.get('object', {}).get('sha') != head:
            raise ValueError('The selected working branch advanced. Refresh before running workspace tools.')
        action = args.get('action')
        if action == 'catalog':
            return dict(common, checks=[dict(index=i, argv=cmd) for i, cmd in enumerate(commands)], network='none')
        name = args.get('request_id')
        if action not in {'run', 'status'} or not isinstance(name, str) or not re.fullmatch('[A-Za-z0-9_-]{1,64}', name):
            raise ValueError('Use catalog, or run/status with a stable request_id')
        key = fingerprint([owner, name])
        if action == 'run':
            index, edits = args.get('check_index'), args.get('changes', [])
            if type(index) is not int or not 0 <= index < len(commands) or not isinstance(edits, list) or len(edits)>20:
                raise ValueError('Select one approved check and up to twenty candidate edits')
            paths = set()
            for edit in edits:
                if not isinstance(edit, dict) or set(edit) != {'path','expected_blob_sha','content'}:
                    raise ValueError('Changes require a path, exact expected blob and text or deletion')
                path = safe_path(edit['path'])
                sha, content = edit['expected_blob_sha'], edit['content']
                if (path in paths or sha is not None and not re.fullmatch('[0-9a-f]{40}', str(sha))
                        or content is None and sha is None or content is not None and (not isinstance(content,str) or '\x00' in content)):
                    raise ValueError('Invalid candidate change')
                paths.add(path)
            signature = fingerprint([common,index,edits])
        else:
            signature = None
        def claim(db):
            self.schema(db)
            db.execute("DELETE FROM engineering_profile_checks WHERE updated<?", (time.time()-7*86400,))
            row = db.execute('SELECT fingerprint,boot,state,receipt FROM engineering_profile_checks WHERE owner=? AND request_id=?', (owner,name)).fetchone()
            if row:
                receipt = json.loads(EngineeringCipher.decrypt(db,key,row[3]))
                if any(receipt.get(k) != v for k,v in common.items()) or signature and signature != row[0]:
                    raise ValueError('Workspace check identity changed')
                if row[2] == 'RUNNING' and row[1] != self.boot:
                    receipt['state'] = 'INTERRUPTED'
                return False,receipt
            if action != 'run':
                raise ValueError('Unknown workspace check')
            if db.execute("SELECT COUNT(*) FROM engineering_profile_checks WHERE state='RUNNING' AND boot=? AND updated>?", (self.boot,time.time()-2000)).fetchone()[0] >= 2:
                raise ValueError('Two host checks are active. Resume after one finishes.')
            if db.execute('SELECT COUNT(*) FROM engineering_profile_checks WHERE owner=?', (owner,)).fetchone()[0] >= 512:
                raise ValueError('Workspace check retention allowance reached')
            receipt = dict(common, environment_profile=environment, state='RUNNING', request_id=name, check_index=index, started_at=time.time())
            db.execute('INSERT INTO engineering_profile_checks VALUES(?,?,?,?,?,?,?)',(owner,name,signature,self.boot,'RUNNING',time.time(),EngineeringCipher.encrypt(db,key,json.dumps(receipt))))
            return True,receipt
        start,receipt = self.store.tx(claim)
        if start:
            threading.Thread(target=self.run, args=(owner, name, key, receipt.copy(), edits), daemon=True).start()
        return receipt

    def run(self, owner, name, key, receipt, edits):
        backend = self.service.backend
        repo, head = receipt['repository'], receipt['head_sha']
        root = '/repos/' + repo
        deadline = time.time()+1800
        job = dict(id='profile_'+key[:32], principal=owner, state='IMPLEMENT', deadline=deadline,
                   request={'repository':repo, 'branch':receipt['branch'], 'task_scope':'implement'},
                   data={'head_sha':head, 'admission':{'policy_sha256':receipt['policy_sha256']}})
        service = self
        class Cancel:
            observed = 0
            denied = False
            def is_set(self):
                if time.monotonic()-self.observed > 2:
                    self.observed = time.monotonic()
                    try:
                        self.denied = service.admitted(owner, repo)[0] != receipt['policy_sha256']
                    except (ValueError, OSError, PermissionError):
                        self.denied = True
                return self.denied or backend.stopping.is_set() or time.time() >= deadline
            def wait(self, seconds):
                backend.stopping.wait(seconds)
                return self.is_set()
        cancel = Cancel()
        try:
            commit = backend.api(root+'/git/commits/'+head, read_only=True)
            tree = commit['tree']['sha']
            prepare_source(job, backend.token(read_only=True), tree, cancel)
            changes = []
            if edits:
                observed = backend.api(root+'/git/trees/'+tree+'?recursive=1', read_only=True)
                if observed.get('truncated') is not False:
                    raise ValueError('Repository tree is incomplete')
                entries = {e['path']:e for e in observed['tree']}
                for edit in edits:
                    entry = entries.get(edit['path'], {})
                    if entry.get('sha') != edit['expected_blob_sha'] or entry.get('mode', '100644') not in {'100644','100755'}:
                        raise ValueError('Candidate source changed')
                    changes.append(dict(edit, mode=entry.get('mode','100644')))
            result = validate(job, changes, cancel=cancel, command_index=receipt['check_index'])
            cmd = next(iter(result.get('commands', [])), {})
            receipt.update(**named_test_results(cmd), changed_path_count=len(changes), state=result['status'], patch_identity=patch_identity(head, changes),
                           exit_code=cmd.get('exit_code'), output_sha256=cmd.get('output_sha256'),
                           output=cmd.get('output_tail','')[-6000:], scope='exploratory')
        except Exception as error:
            receipt.update(state='INTERRUPTED' if cancel.is_set() else 'FAILED', error_type=type(error).__name__)
        receipt['finished_at'] = time.time()
        self.store.tx(lambda db: db.execute('UPDATE engineering_profile_checks SET state=?,updated=?,receipt=? WHERE owner=? AND request_id=? AND boot=?',
            (receipt['state'],time.time(),EngineeringCipher.encrypt(db,key,json.dumps(receipt)),owner,name,self.boot)))
