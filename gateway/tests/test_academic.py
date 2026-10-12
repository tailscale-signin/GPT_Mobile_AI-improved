import json
import socket
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.academic import AcademicRouter, AcademicError, QuotaRegistry, digest, merge_papers, paper, public_fetch

FIXTURES = Path(__file__).resolve().parents[2] / 'app/src/test/resources/academic'

class AcademicTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.router = AcademicRouter(self.temp.name)

    def test_live_core_fixtures_normalize_as_metadata_only(self):
        for provider in ('openalex', 'crossref', 'arxiv', 'europepmc', 'datacite'):
            path = FIXTURES / (provider + ('.xml' if provider == 'arxiv' else '.json'))
            with self.subTest(provider=provider):
                records = self.router.parse(provider, path.read_bytes())
                self.assertTrue(records[0]['title'])
                self.assertEqual('metadata_only', records[0]['sourceAccess'])
                self.assertEqual([provider], records[0]['sourceProviders'])

    def test_exact_work_dedupe_retains_providers_and_versions(self):
        a = paper('Example', 'https://doi.org/10.1234/example', identifier='10.1234/example', provider='openalex', providerReceipts=['a'])
        b = dict(a, sourceProviders=['crossref'], providerReceipts=['b'])
        preprint = paper('Example', 'https://arxiv.org/abs/2401.00001v2', ids={'arxiv':'2401.00001v2'}, provider='arxiv', preprint=True)
        records = merge_papers([a,b,preprint])
        self.assertEqual(2,len(records)); self.assertEqual(['a','b'],records[0]['providerReceipts'])
        self.assertNotEqual(records[0]['versionId'],records[1]['versionId'])

    def test_failing_sibling_is_partial_not_fabricated_success(self):
        def fetch(url, **_):
            if 'openalex' in url: return 429, {'retry-after':'600'}, b'', url
            return 200, {}, (FIXTURES / 'crossref.json').read_bytes(), url
        router=AcademicRouter(self.temp.name, fetch=fetch, enabled=['openalex','crossref'])
        result=router.search({'query':'research','enabledProviderIds':['openalex','crossref']},lambda:False)
        self.assertEqual('partial', result['status']); self.assertTrue(result['papers'])
        limited=next(p for p in result['providers'] if p['id']=='openalex')
        self.assertEqual('rate_limited',limited['state']); self.assertGreater(limited['retryAtMs'],0)

    def test_durable_cooldown_and_no_wrapper_reset(self):
        clock=lambda:10000
        path=Path(self.temp.name)/'quota-test.db'
        first=QuotaRegistry(path,clock); first.defer('openalex',600)
        second=QuotaRegistry(path,clock)
        with self.assertRaises(AcademicError) as caught: second.reserve('openalex')
        self.assertEqual('rate_limited',caught.exception.code)

    def test_atomic_reservation_allows_only_one_concurrent_arxiv_call(self):
        quota=QuotaRegistry(Path(self.temp.name)/'atomic.db',lambda:10000)
        results=[]
        def reserve():
            try: quota.reserve('arxiv'); results.append(True)
            except AcademicError: results.append(False)
        threads=[threading.Thread(target=reserve) for _ in range(8)]
        for t in threads:t.start()
        for t in threads:t.join()
        self.assertEqual(1,sum(results))

    def test_cancelled_and_disabled_calls_do_not_fetch(self):
        calls=[]
        router=AcademicRouter(self.temp.name,fetch=lambda *a,**k:calls.append(a))
        with self.assertRaises(AcademicError):router.execute('deep_research_search_papers',{'query':'test'},lambda:True)
        result=router.search({'query':'test','enabledProviderIds':['none']},lambda:False)
        self.assertEqual([],result['papers']);self.assertEqual('partial',result['status']);self.assertEqual([],calls)

    def test_broad_and_excluded_source_presets_rejected(self):
        for sources in (['all'],['sci_hub'],['unpaywall']):
            with self.assertRaises(AcademicError):self.router.search({'query':'test','enabledProviderIds':sources},lambda:False)

    def test_quote_requires_captured_exact_version_and_intact_hash(self):
        args={'paperId':'doi:10.1234/test','versionId':'v1','quote':'exact passage'}
        self.assertEqual('not_read',self.router.verify(args)['verdict'])
        evidence={**args,'sourceUrl':'https://arxiv.org/abs/2401.00001','text':'An exact passage survives.','textHash':digest('An exact passage survives.'),'section':'page 1'}
        path=Path(self.temp.name)/(digest(args['paperId']+'|'+args['versionId'])+'.json');path.write_text(json.dumps(evidence))
        self.assertEqual('quote_verified',self.router.verify(args)['verdict'])
        self.assertEqual(3,self.router.verify(args)['offset'])
        self.assertEqual('not_read',self.router.verify(dict(args,versionId='v2'))['verdict'])
        evidence['text']='changed';path.write_text(json.dumps(evidence))
        self.assertEqual('snapshot_integrity_failed',self.router.verify(args)['verdict'])

    def test_metadata_and_html_disguised_as_pdf_are_not_read(self):
        record=paper('Example','https://arxiv.org/abs/2401.00001',ids={'arxiv':'2401.00001'},provider='arxiv',openAccessUrl='https://arxiv.org/pdf/2401.00001')
        self.router.papers[record['paperId']]=record
        self.router.fetch=lambda url,**_:(200,{'content-type':'text/html'},b'<html>Access denied</html>',url)
        with self.assertRaises(AcademicError) as caught:self.router.read_paper(record['paperId'],{},lambda:False)
        self.assertEqual('invalid_pdf_signature',caught.exception.code)

    def test_bounded_pdf_worker_preserves_page_and_exact_quote(self):
        if not self.router._pdf_reader():
            self.skipTest('pdftotext is optional')
        stream = b'BT /F1 12 Tf 30 100 Td (Measured synthetic academic evidence.) Tj ET'
        objects = [b'<< /Type /Catalog /Pages 2 0 R >>', b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
                   b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
                   b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
                   b'<< /Length ' + str(len(stream)).encode() + b' >>\nstream\n' + stream + b'\nendstream']
        body = b'%PDF-1.4\n'; offsets = []
        for index, obj in enumerate(objects, 1):
            offsets.append(len(body)); body += str(index).encode() + b' 0 obj\n' + obj + b'\nendobj\n'
        start = len(body)
        body += b'xref\n0 6\n0000000000 65535 f \n' + b''.join(f'{offset:010} 00000 n \n'.encode() for offset in offsets)
        body += b'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n' + str(start).encode() + b'\n%%EOF'
        record = paper('Synthetic', 'https://arxiv.org/abs/2401.00001v1', ids={'arxiv':'2401.00001v1'}, provider='arxiv', openAccessUrl='https://arxiv.org/pdf/2401.00001v1')
        self.router.papers[record['paperId']] = record
        self.router.fetch = lambda url, **_: (200, {'content-type':'application/pdf'}, body, url)
        evidence = self.router.read_paper(record['paperId'], {}, lambda: False)
        self.assertEqual('partially_read', evidence['retrievalStatus'])
        self.assertEqual(1, evidence['sections'][0]['page'])
        self.assertEqual('quote_verified', self.router.verify({'paperId': evidence['paperId'], 'versionId': evidence['versionId'], 'quote':'Measured synthetic academic evidence.'})['verdict'])

    def test_ssrf_nonpublic_resolution_and_wrong_scheme_denied(self):
        with patch('socket.getaddrinfo',return_value=[(socket.AF_INET,socket.SOCK_STREAM,0,'',('127.0.0.1',443))]):
            with self.assertRaises(AcademicError):public_fetch('https://arxiv.org/pdf/1')
        with self.assertRaises(AcademicError):public_fetch('http://arxiv.org/pdf/1')
        with self.assertRaises(AcademicError):public_fetch('https://169.254.169.254/latest/meta-data/')

    def test_citation_edges_do_not_assert_support(self):
        self.router.fetch=lambda url,**_:(200,{},json.dumps([{'citing':'doi:10.1234/a','cited':'doi:10.1234/b'}]*40).encode(),url)
        result=self.router.citations({'identifier':'10.1234/a'},lambda:False)
        self.assertEqual(30,len(result['edges']));self.assertTrue(result['truncated'])
        self.assertEqual('citation_edge_not_claim_support',result['relation'])

    def test_domains_apply_to_reused_evidence(self):
        args={'paperId':'p','versionId':'v','quote':'data','excludeDomains':['arxiv.org']}
        path=Path(self.temp.name)/(digest('p|v')+'.json');path.write_text(json.dumps({'versionId':'v','sourceUrl':'https://arxiv.org/abs/1','text':'data','textHash':digest('data'),'section':'page 1'}))
        with self.assertRaises(AcademicError):self.router.verify(args)

if __name__=='__main__':unittest.main()

class GoalDrivenAcademicTests(unittest.TestCase):
    def test_page_eleven_is_a_valid_provider_request(self):
        with tempfile.TemporaryDirectory() as directory:
            seen = []
            def fetch(url, **_):
                seen.append(url)
                return 200, {}, b'{"results":[]}', url
            router = AcademicRouter(directory, fetch=fetch, enabled=['openalex'])
            result = router.execute('deep_research_search_papers', {'query':'test', 'cursor':'11', 'enabledProviderIds':['openalex']})
            self.assertTrue(any('page=11' in url for url in seen))
            self.assertEqual(1, result['researchAccounting']['physicalRequests'])
            self.assertEqual(len(b'{"results":[]}'), result['researchAccounting']['downloadedBytes'])

    def test_explicit_request_allowance_blocks_dispatch_and_status_advertises_policy(self):
        with tempfile.TemporaryDirectory() as directory:
            def never_fetch(*args, **kwargs):
                self.fail('zero allowance must not dispatch')
            router = AcademicRouter(directory, fetch=never_fetch, enabled=['openalex'])
            result = router.execute('deep_research_search_papers', {'query':'test', 'enabledProviderIds':['openalex'], 'researchRequestAllowance':0})
            self.assertEqual(0, result['researchAccounting']['physicalRequests'])
            self.assertEqual('user_request_limit', result['providers'][0]['state'])
            self.assertIn(3, router.execute('deep_research_status', {})['researchPolicyVersions'])
