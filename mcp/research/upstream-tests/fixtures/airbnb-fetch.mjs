const listing = {
  niobeClientData: [['fixture', { data: { presentation: { stayProductDetailPage: { sections: { sections: [
    { sectionId: 'DESCRIPTION_DEFAULT', section: { htmlDescription: { htmlText: 'Dependency fixture description' } } }
  ] } } } } }]],
  picture: 'https://a0.muscache.com/im/pictures/dependency-fixture.jpg',
  reviews: [{ comments: 'Dependency fixture review' }]
};

// Every provider request is handled here; an unexpected request never reaches the network.
export default async function fetch(input) {
  const url = new URL(String(input));
  if (url.href === 'https://www.airbnb.com/robots.txt') return new Response('User-agent: *\nDisallow: /rooms/blocked-fixture\n');
  if (url.origin === 'https://www.airbnb.com' && url.pathname === '/rooms/dependency-fixture' && url.searchParams.get('adults') === '2') {
    return new Response('<html><script id="data-deferred-state-0" type="application/json">' + JSON.stringify(listing) + '</script></html>');
  }
  throw new Error('Unexpected provider request in dependency fixture');
}
