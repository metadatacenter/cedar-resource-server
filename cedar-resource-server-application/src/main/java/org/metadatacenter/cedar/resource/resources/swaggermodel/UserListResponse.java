package org.metadatacenter.cedar.resource.resources.swaggermodel;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Documentation-only model for the response of the users endpoint.
 *
 * <p>The endpoint returns an envelope with the users under a single key rather than a bare array,
 * so this bean names that key and points at the user description. It has no runtime role.</p>
 */
@Schema(name = "UserListResponse", description = "Every user of the system, under a single key.")
public class UserListResponse {

  @Schema(name = "users", description = "The users, in the order the workspace graph returns them.")
  private List<User> users;

  public List<User> getUsers() {
    return users;
  }

  public void setUsers(List<User> users) {
    this.users = users;
  }
}
