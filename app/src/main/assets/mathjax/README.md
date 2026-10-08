# Offline MathJax assets

The bundled `tex-svg.js` and New Computer Modern SVG dynamic font modules use MathJax 4.1.2. The dynamic modules were obtained from the official npm package `@mathjax/mathjax-newcm-font@4.1.2` (https://github.com/mathjax/MathJax-fonts). The package declares Apache-2.0; the Apache license in this directory applies to these assets.

`MathJaxView` maps `[fonts]` to this Android asset directory and renders with `tex2svgPromise`, because MathJax 4 loads extended glyphs asynchronously. All SVG dynamic modules are bundled so extended symbols do not attempt a CDN request while WebView network loading is disabled.

A headless browser check rendered square/star/mho, double-struck R, integrals and Greek symbols using only local files.
