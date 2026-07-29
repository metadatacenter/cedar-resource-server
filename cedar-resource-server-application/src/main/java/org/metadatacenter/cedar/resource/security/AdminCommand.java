package org.metadatacenter.cedar.resource.security;

import org.metadatacenter.exception.CedarException;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.security.model.auth.CedarPermission;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

/**
 * The resource server's administrative index/ontology commands and the permission each one requires,
 * with the authorization gate itself.
 *
 * <p>Each command used to carry its own inline {@code c.must(c.user()).be(LoggedIn); c.must(c.user())
 * .have(...)} pair, and that per-route copy has slipped twice: {@code load-valuesets-ontology} once
 * shipped with the check commented out (open to any logged-in user), and {@code generate-empty-rules-
 * index} once demanded {@code SEARCH_INDEX_REINDEX} instead of {@code RULES_INDEX_REINDEX}. Binding the
 * command to its permission in one table, and running the assertion from one method, removes the place
 * those slips lived: the whole gate is visible here rather than scattered, and a route authorizes with a
 * single self-describing call.
 *
 * <p>{@link #enforce} preserves the exact behaviour of the inline checks — the same two assertions in the
 * same order — so a denial still throws {@code CedarAssertionException} and the CedarExceptionMapper
 * still answers 401 to an anonymous caller and 403 to a logged-in caller without the permission. That
 * contract is pinned by {@code AdminCommandAuthorizationMatrixTest}.
 */
public enum AdminCommand {

  LOAD_VALUESETS_ONTOLOGY(CedarPermission.SEARCH_INDEX_REINDEX),
  REGENERATE_SEARCH_INDEX(CedarPermission.SEARCH_INDEX_REINDEX),
  GENERATE_EMPTY_SEARCH_INDEX(CedarPermission.SEARCH_INDEX_REINDEX),
  REGENERATE_RULES_INDEX(CedarPermission.RULES_INDEX_REINDEX),
  GENERATE_EMPTY_RULES_INDEX(CedarPermission.RULES_INDEX_REINDEX);

  private final CedarPermission permission;

  AdminCommand(CedarPermission permission) {
    this.permission = permission;
  }

  /**
   * Assert that the caller of this command is logged in and holds the command's permission. This is the
   * single admin-authorization gate for the resource server; a denial throws and maps to 401 (not logged
   * in) or 403 (logged in without the permission).
   */
  public void enforce(CedarRequestContext c) throws CedarException {
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(permission);
  }
}
