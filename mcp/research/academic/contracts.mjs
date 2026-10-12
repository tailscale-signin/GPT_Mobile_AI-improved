import { z } from 'zod';

export const operations = ['search_papers', 'get_paper', 'read_paper', 'citations', 'verify_evidence', 'status'];
const common = {
  researchRequestAllowance: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER).optional(),
  researchDownloadAllowance: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER).optional(),
  researchPolicyVersion: z.literal(3).optional(),
  researchSourceGoal: z.number().int().positive().max(2147483647).optional(),
  researchRunId: z.string().max(200).optional(),
  researchPolicyFingerprint: z.string().regex(/^[a-f0-9]{64}$/).optional(),
  ephemeral: z.boolean().optional(),
  depth: z.number().int().min(1).max(100).optional(),
  maxProviderCalls: z.number().int().min(1).max(64).optional(),
  enabledProviderIds: z.array(z.string().max(40)).max(256).optional(),
  includeDomains: z.array(z.string().regex(/^[a-z0-9.-]+$/i).max(200)).max(20).optional(),
  excludeDomains: z.array(z.string().regex(/^[a-z0-9.-]+$/i).max(200)).max(20).optional()
};
const identifier = z.string().min(1).max(500);
const schemas = {
  search_papers: z.object({ ...common, query: z.string().min(1).max(500), scope: z.literal('papers').optional(),
    discipline: z.array(z.string().max(60)).max(5).optional(), maxResults: z.number().int().min(1).max(50).optional(),
    cursor: z.string().regex(/^[1-9][0-9]{0,9}$/).refine(v => Number(v) <= 2147483647).optional(), fromYear: z.number().int().min(1500).max(3000).optional(),
    toYear: z.number().int().min(1500).max(3000).optional(), reviewStatus: z.enum(['any', 'unknown', 'preprint', 'peer_reviewed']).optional()
  }).strict().refine(a => a.fromYear === undefined || a.toYear === undefined || a.fromYear <= a.toYear, 'Invalid year interval'),
  get_paper: z.object({ ...common, identifier }).strict(),
  read_paper: z.object({ ...common, identifier }).strict(),
  citations: z.object({ ...common, identifier, direction: z.enum(['references', 'citations']).optional() }).strict(),
  verify_evidence: z.object({ ...common, paperId: identifier, versionId: z.string().min(1).max(700), quote: z.string().min(1).max(4000) }).strict(),
  status: z.object(common).strict()
};
export function validateArguments(name, args) {
  const schema = schemas[name.replace(/^deep_research_/, '')];
  if (!name.startsWith('deep_research_') || !schema) throw new Error('Unknown academic operation.');
  return schema.parse(args);
}
