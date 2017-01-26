package org.metadatacenter.cedar.resource.search;

public class SearchPermissionService {

  public void resourceCreated(String id) {
  }

  public void folderCreated(String id) {
  }

  public void resourceCopied(String id) {
  }

  public void resourceMoved(String id) {
  }

  public void resourceDeleted(String id) {
  }

  public void folderDeleted(String id) {
  }

  public void folderMoved(String id) {
  }

  public void folderPermissionsChanged(String id) {
    // Check if this was a real change. Check this at the calling side
  }

  public void resourcePermissionsChanged(String id) {
    // Check if this was a real change. Check this at the calling side
  }

  public void userCreated(String id) {
    // TODO: this is probably the UUID here
    // This will not be the case once we change the id in the profile.
  }
}
