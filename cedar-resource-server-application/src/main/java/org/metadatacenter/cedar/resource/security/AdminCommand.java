package org.metadatacenter.cedar.resource.security;

import org.metadatacenter.exception.CedarException;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.security.model.auth.CedarPermission;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

/**
 * The resource server's administrative commands and the permission each one requires, with the
 * authorization gate itself.
 *
 * <p>Each command used to carry its own inline {@code c.must(c.user()).be(LoggedIn); c.must(c.user())
 * .have(...)} pair, and that per-route copy has slipped three times: {@code load-valuesets-ontology} once
 * shipped with the check commented out (open to any logged-in user), {@code generate-empty-rules-index}
 * once demanded {@code SEARCH_INDEX_REINDEX} instead of {@code RULES_INDEX_REINDEX}, and
 * {@code auth-user-callback} asserted only {@code LoggedIn} while carrying a TODO about the check it was
 * missing. Binding the command to its permission in one table, and running the assertion from one method,
 * removes the place those slips lived: the whole gate is visible here rather than scattered, and a route
 * authorizes with a single self-describing call.
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
  GENERATE_EMPTY_RULES_INDEX(CedarPermission.RULES_INDEX_REINDEX),

  /**
   * Taking back a claim that passed its deadline frees the index for the next rebuild, so it asks for the
   * same permission as the rebuild it unblocks rather than one of its own.
   */
  RESET_SEARCH_INDEX_JOB(CedarPermission.SEARCH_INDEX_REINDEX),
  RESET_RULES_INDEX_JOB(CedarPermission.RULES_INDEX_REINDEX),
  RESET_VALUESETS_IMPORT(CedarPermission.SEARCH_INDEX_REINDEX),

  /**
   * The Keycloak event listener's user-provisioning callback. {@code USER_UPDATE} comes only from the
   * {@code userAdministrator} role, which the {@code normal} blueprint grants to nobody and the built-in
   * admin holds. The listener already authenticates with {@code CEDAR_ADMIN_USER_API_KEY}, so this asks
   * for no more than its only legitimate caller already presents.
   */
  AUTH_USER_CALLBACK(CedarPermission.USER_UPDATE);

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
