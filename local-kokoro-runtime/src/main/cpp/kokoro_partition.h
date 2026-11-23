#ifndef MELO_KOKORO_PARTITION_H_
#define MELO_KOKORO_PARTITION_H_

#include <algorithm>
#include <cstddef>
#include <stdexcept>
#include <vector>

namespace kokoro_runtime {

// A fused group must be convex: no path between two of its nodes may leave the
// group. QNN's node-unit expansion can violate that requirement around dynamic
// Kokoro branches. Use actual graph edges, not node names or guessed operators.
// All input indices refer to a stable topological order supplied by ORT.
inline std::vector<std::vector<size_t>> ConvexSpeechPartitions(
    const std::vector<std::vector<size_t>> &parents,
    const std::vector<size_t> &proposed) {
  if (parents.empty() || parents.size() > 100000 || proposed.empty())
    throw std::invalid_argument("Invalid speech partition size");
  std::vector<bool> supported(parents.size(), false);
  for (const auto node : proposed) {
    if (node >= parents.size() || supported[node])
      throw std::invalid_argument("Invalid speech partition node");
    supported[node] = true;
  }
  std::vector<size_t> barriers(parents.size(), 0);
  std::vector<std::vector<size_t>> groups(1);
  size_t edges = 0;
  for (size_t node = 0; node < parents.size(); ++node) {
    for (const auto parent : parents[node]) {
      if (parent >= node || ++edges > 1000000)
        throw std::invalid_argument("Invalid speech graph topology");
      // Crossing OUT of the proposed set starts a new rank. Along any path the
      // rank never decreases, so re-entry cannot share its ancestor's group.
      barriers[node] = std::max(barriers[node], barriers[parent] +
          static_cast<size_t>(supported[parent] && !supported[node]));
    }
    if (supported[node]) {
      if (groups.size() <= barriers[node]) groups.resize(barriers[node] + 1);
      groups[barriers[node]].push_back(node);
    }
  }
  groups.erase(std::remove_if(groups.begin(), groups.end(),
      [](const auto &group) { return group.empty(); }), groups.end());
  return groups;
}

}  // namespace kokoro_runtime
#endif
