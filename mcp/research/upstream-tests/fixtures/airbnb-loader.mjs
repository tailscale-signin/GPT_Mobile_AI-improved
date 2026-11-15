export async function resolve(specifier, context, nextResolve) {
  if (specifier === 'node-fetch') return { url: new URL('./airbnb-fetch.mjs', import.meta.url).href, shortCircuit: true };
  return nextResolve(specifier, context);
}
