#include "kokoro_partition.h"
#include <cassert>
#include <random>
#include <set>

using kokoro_runtime::ConvexSpeechPartitions;

int main() {
  // Captured failure: a supported shortcut crosses an unsupported middle node.
  const std::vector<std::vector<size_t>> diamond{{}, {0}, {0, 1}, {2}};
  const auto repaired = ConvexSpeechPartitions(diamond, {0, 2, 3});
  assert((repaired == std::vector<std::vector<size_t>>{{0}, {2, 3}}));
  assert((ConvexSpeechPartitions(diamond, {0, 1, 2, 3}) ==
      std::vector<std::vector<size_t>>{{0, 1, 2, 3}}));
  // An unrelated unsupported root must not fragment a valid accelerator group.
  assert((ConvexSpeechPartitions({{}, {}, {0}, {2}}, {0, 2, 3}) ==
      std::vector<std::vector<size_t>>{{0, 2, 3}}));
  for (const auto invalid : {std::vector<size_t>{0, 0}, std::vector<size_t>{4}}) {
    bool rejected = false;
    try { ConvexSpeechPartitions(diamond, invalid); } catch (const std::invalid_argument &) { rejected = true; }
    assert(rejected);
  }
  bool rejected = false;
  try { ConvexSpeechPartitions({{1}, {0}}, {0, 1}); } catch (const std::invalid_argument &) { rejected = true; }
  assert(rejected);

  // Compare the invariant to exhaustive paths through many small branching
  // graphs. This catches re-entry through implicit/control-flow-like edges.
  std::mt19937 random(0x4b4f4b4f);
  for (int trial = 0; trial < 3000; ++trial) {
    constexpr size_t n = 10;
    std::vector<std::vector<size_t>> parents(n);
    std::vector<size_t> proposed;
    for (size_t i = 0; i < n; ++i) {
      if ((random() & 1) || i == 0) proposed.push_back(i);
      for (size_t j = 0; j < i; ++j) if (random() % 4 == 0) parents[i].push_back(j);
    }
    const auto groups = ConvexSpeechPartitions(parents, proposed);
    std::set<size_t> covered;
    for (const auto &group : groups) {
      const std::set<size_t> selected(group.begin(), group.end());
      for (auto node : group) assert(covered.insert(node).second);
      std::vector<bool> reaches(n), escaped(n);
      for (size_t i = 0; i < n; ++i) {
        for (auto p : parents[i]) {
          reaches[i] = reaches[i] || reaches[p];
          escaped[i] = escaped[i] || escaped[p] || (reaches[p] && !selected.count(i));
        }
        if (selected.count(i)) { assert(!escaped[i]); reaches[i] = true; }
      }
    }
    assert(covered == std::set<size_t>(proposed.begin(), proposed.end()));
  }
}
