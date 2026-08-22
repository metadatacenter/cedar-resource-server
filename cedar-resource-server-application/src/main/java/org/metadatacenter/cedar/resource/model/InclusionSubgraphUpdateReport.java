package org.metadatacenter.cedar.resource.model;

import org.metadatacenter.model.request.inclusionsubgraph.InclusionSubgraphResponse;

import java.util.List;

/**
 * The result of an inclusion subgraph propagation: the affected tree it worked from, and what became of
 * each target it wrote.
 *
 * <p>The tree alone describes the plan rather than the outcome, since it is built before the first write.
 * Returning it as the whole answer is what let a rejected write pass for a successful one.
 */
public class InclusionSubgraphUpdateReport {

  private final InclusionSubgraphResponse tree;
  private final List<InclusionSubgraphUpdateOutcome> outcomes;

  public InclusionSubgraphUpdateReport(InclusionSubgraphResponse tree, List<InclusionSubgraphUpdateOutcome> outcomes) {
    this.tree = tree;
    this.outcomes = outcomes;
  }

  public InclusionSubgraphResponse getTree() {
    return tree;
  }

  public List<InclusionSubgraphUpdateOutcome> getOutcomes() {
    return outcomes;
  }

  /** True when no target was refused by the artifact server. */
  public boolean isComplete() {
    return outcomes.stream().noneMatch(o -> o.getStatus() == InclusionSubgraphUpdateOutcome.Status.FAILED);
  }
}
