# Chat source icons

52 common reference sites and 15 search providers have bundled icons. Other sites and custom engines receive a themed globe/search icon. The response shows only observed sources and engines; the catalog is not a list of sources used for every answer. Icons load locally, with no favicon service or background page requests.

`chat_source_*.xml` vectors are converted from [Simple Icons](https://github.com/simple-icons/simple-icons) at commit `98820a4dc8c363ca72fa2c0d294ea4a0a9bba75d`, under [CC0 1.0](licenses/simple-icons-CC0.md). Tavily, Exa, Jina, Firecrawl, and Microsoft reuse existing app assets.

Source URLs come from persisted successful search/reader/delegation tool results and answer links. Repeated URLs ignore fragments and common tracking parameters; distinct pages on the same site remain separate in the picker. Combined answers retain source tool results from the participating candidate revisions. Compact links hide the scheme, tracking query, and fragment visually; tapping still opens the original URL.

On-device review: exercise the icon-origin animation near both screen edges, in landscape and split screen, at large font sizes, with the keyboard visible, and with more than 30 results. Test outside tap, system back, and close-button dismissal. Confirm source lists after reopening a conversation, retrying a response, inspecting revisions, and switching combined-mode profiles.
