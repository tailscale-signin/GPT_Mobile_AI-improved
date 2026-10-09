/** Recover public media from the exact page the upstream already fetched. No extra requests. */
export function extractListingMedia(root) {
  const photos = new Set();
  const reviews = new Set();
  let visited = 0;
  function visit(value, path = [], depth = 0) {
    if (++visited > 20000 || depth > 18 || !value) return;
    if (Array.isArray(value)) { value.slice(0, 200).forEach(item => visit(item, path, depth + 1)); return; }
    if (typeof value !== 'object') return;
    for (const [key, child] of Object.entries(value).slice(0, 150)) {
      if (typeof child === 'string' && ['picture', 'baseUrl', 'imageUrl', 'image_url', 'url', 'src'].includes(key)) {
        try {
          const url = new URL(child);
          if (url.protocol === 'https:' && !url.username && !url.password && (url.hostname === 'muscache.com' || url.hostname.endsWith('.muscache.com')) && /\/(?:im\/)?pictures\//.test(url.pathname)) photos.add(url.href);
        } catch { /* Unsupported media stays absent. */ }
      }
      if (typeof child === 'string' && ['comments', 'reviewText', 'text'].includes(key) && path.some(part => /^(reviews?|reviewList)$/i.test(part))) reviews.add(child.slice(0, 2000));
      if (typeof child === 'object') visit(child, [...path, key], depth + 1);
    }
  }
  visit(root);
  return { photos: [...photos].slice(0, 20), reviews: [...reviews].slice(0, 50).map(comments => ({ comments })) };
}

export function enhanceAirbnbSource(source) {
  const search = '.map((result: any) => flattenArraysInObject(pickBySchema(result, allowSearchResultSchema)))';
  const details = 'details = extracted;';
  if (!source.includes(search) || !source.includes(details)) throw new Error('Pinned Airbnb source contract changed; review the extraction adapter before building.');
  const helper = extractListingMedia.toString().replace('function extractListingMedia(root)', 'function extractListingMedia(root: any): { photos: string[]; reviews: { comments: string }[] }')
    .replace('new Set()', 'new Set<string>()').replace('new Set()', 'new Set<string>()')
    .replace('function visit(value, path = [], depth = 0)', 'function visit(value: any, path: string[] = [], depth = 0)')
    .replace('if (Array.isArray(value)) {', 'if (Array.isArray(value)) {');
  // Preserve all upstream robots.txt checks, schema validation, date/guest parameters and error handling.
  return source.replace(search, '.map((result: any) => ({ ...flattenArraysInObject(pickBySchema(result, allowSearchResultSchema)), ...extractListingMedia(result) }))')
    .replace(details, 'details = [...extracted, { id: "GPTMOBILE_MEDIA", ...extractListingMedia(clientData) }];') + '\n' + helper + '\n';
}
