package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.core.type.TypeReference;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.metadatacenter.cedar.resource.deletion.RecursiveFolderDeletion;
import org.metadatacenter.cedar.resource.deletion.RecursiveFolderDeletion.*;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.id.*;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.folderserver.basic.*;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.*;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.server.security.model.permission.resource.ResourceCapability;
import org.metadatacenter.util.http.*;
import org.metadatacenter.util.json.JsonMapper;
import java.util.*;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

/** Explicit recursive API; the historical DELETE endpoint remains empty-folder-only. */
@Path("/folders/{folder_id}/deletion")
@Produces("application/json")
@Tag(name = "Folders")
@SecurityRequirement(name = "api_key")
public final class RecursiveFolderResource extends AbstractResourceServerResource {
  public RecursiveFolderResource(CedarConfig config) { super(config); }

  @GET
  @Operation(summary = "Inventory and check a recursive folder deletion")
  @ApiResponse(responseCode = "200", description = "Complete deletion inventory and blockers", content = @Content(schema = @Schema(implementation = Plan.class)))
  public Response preview(@PathParam("folder_id") String id) throws Exception {
    CedarRequestContext c = context(id);
    id = linkedDataUtil.resolveResourceId(org.metadatacenter.model.CedarResourceType.FOLDER, id);
    try {
      return Response.ok(service(c, id).plan()).header("Cache-Control", "no-store").build();
    } catch (IllegalStateException e) {
      return problem(409, Reason.INVENTORY_UNAVAILABLE);
    }
  }
  @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  public record Confirmation(String token) {}
  @POST
  @Consumes("application/json")
  @Operation(summary = "Delete exactly the confirmed eligible folder inventory")
  @ApiResponse(responseCode = "200", description = "Completed or stopped deletion with confirmed progress", content = @Content(schema = @Schema(implementation = Outcome.class)))
  public Response delete(@PathParam("folder_id") String id, Confirmation confirmation) throws Exception {
    CedarRequestContext c = context(id);
    id = linkedDataUtil.resolveResourceId(org.metadatacenter.model.CedarResourceType.FOLDER, id);
    c.request().getRequestBody().mustHaveOnly("token");
    if (confirmation == null || confirmation.token() == null || !confirmation.token().matches("[a-f0-9]{64}"))
      return problem(400, Reason.INVALID_TOKEN);
    Outcome outcome = service(c, id).execute(confirmation.token());
    int status = switch (outcome.status()) { case "changed", "blocked" -> 409; default -> 200; };
    return Response.status(status).entity(outcome).header("Cache-Control", "no-store").build();
  }
  private CedarRequestContext context(String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.FOLDER_DELETE);
    id = linkedDataUtil.resolveResourceId(org.metadatacenter.model.CedarResourceType.FOLDER, id);
    userMustHaveCapabilityOnFolder(c, CedarFolderId.build(id), ResourceCapability.READ_RESOURCE);
    FolderServerFolder root = dataServices.getFolderServiceSession(c).findFolderById(CedarFolderId.build(id));
    if (root.isRoot() || root.isSystem() || root.isUserHome())
      throw new WebApplicationException(problem(400, Reason.PROTECTED_ROOT));
    requireOwner(dataServices.getResourcePermissionServiceSession(c), id);
    return c;
  }
  private static Response problem(int status, Reason reason) {
    return Response.status(status).entity(Map.of("code", reason.code(), "message", reason.message()))
        .header("Cache-Control", "no-store").build();
  }
  private static void requireOwner(ResourcePermissionServiceSession permissions, String root) {
    if (!permissions.userIsOwnerOfResource(CedarFolderId.build(root)))
      throw new WebApplicationException(problem(403, Reason.NOT_OWNER));
  }
  private RecursiveFolderDeletion service(CedarRequestContext c, String root) {
    FolderServiceSession folders = dataServices.getFolderServiceSession(c);
    ResourcePermissionServiceSession permissions = dataServices.getResourcePermissionServiceSession(c);
    return new RecursiveFolderDeletion(new RecursiveFolderDeletion.Store() {
      private List<FileSystemResource> nodes() {
        return folders.findAllDescendantNodesById(CedarFolderId.build(root));
      }
      private Entry entry(FileSystemResource node, boolean content) throws Exception {
        var id = node.getResourceId();
        var path = folders.findNodePathExtract(node);
        int index = -1;
        for (int i = 0; i < path.size(); i++) if (path.get(i).getId().equals(node.getId())) index = i;
        if (index < 0) throw new IllegalStateException("The folder path changed while it was being checked");
        String parent = index == 0 ? "" : path.get(index - 1).getId();
        long revision;
        String etag = "";
        boolean protectedFolder = false;
        if (node instanceof FolderServerFolder folder) {
          var snapshot = folders.findVersionedFolderById(id.asFolderId());
          if (snapshot == null) throw new IllegalStateException("A folder disappeared during inventory");
          revision = snapshot.revision();
          etag = RevisionPreconditionParser.format(revision);
          protectedFolder = folder.isRoot() || folder.isUserHome() || folder.isSystem();
        } else {
          var snapshot = folders.findVersionedArtifactById(id.asArtifactId());
          if (snapshot == null) throw new IllegalStateException("An artifact disappeared during inventory");
          revision = snapshot.revision();
          if (content) {
            String url = cedarConfig.getMicroserviceUrlUtil().getArtifact().getArtifactTypeWithId(node.getType(), id.asArtifactId());
            try (var response = new ArtifactServiceClient(cedarConfig).get(url, c)) {
              EntityUtils.consume(response.getEntity());
              if (response.getCode() != 200 || response.getFirstHeader("ETag") == null)
                throw new IllegalStateException("Artifact revisions could not be fully checked");
              etag = response.getFirstHeader("ETag").getValue();
            }
          }
        }
        boolean readable = permissions.userHasCapability(id, ResourceCapability.READ_RESOURCE);
        boolean deletable = permissions.userHasCapability(id, ResourceCapability.DELETE_RESOURCE)
            && c.getCedarUser().has(deletePermission(node.getType()));
        return new Entry(node.getId(), node.getName(), node.getType().getValue(), parent, index,
            revision, etag, readable, deletable, protectedFolder);
      }
      @Override public Inventory inventory() throws Exception {
        requireOwner(permissions, root);
        List<Entry> entries = new ArrayList<>();
        for (FileSystemResource node : nodes()) entries.add(entry(node, true));
        List<String> templates = entries.stream().filter(e -> e.type().equals("template")).map(Entry::id).toList();
        Map<String, List<String>> references = new TreeMap<>();
        // Bound each internal request without truncating the inventory of a large tree.
        for (int offset = 0; offset < templates.size(); offset += 1000) {
          String url = cedarConfig.getServers().getArtifact().getBase().replaceAll("/$", "") + "/templates/deletion-references";
          try (var response = new ArtifactServiceClient(cedarConfig).post(url, c, JsonMapper.STRICT_MAPPER.writeValueAsString(templates.subList(offset, Math.min(offset + 1000, templates.size())).stream().map(linkedDataUtil::resourceRequestId).toList()))) {
            String body = EntityUtils.toString(response.getEntity());
            if (response.getCode() != 200) throw new IllegalStateException("Template references could not be checked");
            references.putAll(JsonMapper.STRICT_MAPPER.readValue(body, new TypeReference<Map<String, List<String>>>() {}));
          }
        }
        // Detect changes during the scan too, rather than presenting a mix of two different trees.
        Map<String, Entry> checked = new HashMap<>();
        for (FileSystemResource node : nodes()) checked.put(node.getId(), entry(node, false));
        if (checked.size() != entries.size() || entries.stream().anyMatch(e -> !sameGraph(e, checked.get(e.id()))))
          throw new IllegalStateException("The folder changed during inventory. Please try again.");
        requireOwner(permissions, root);
        return new Inventory(entries, references);
      }
      @Override public Step delete(Entry expected) throws Exception {
        // A transfer must revoke the former owner's recursive operation, even if editor access remains.
        if (!permissions.userIsOwnerOfResource(CedarFolderId.build(root)))
          return new Step(false, Reason.NOT_OWNER);
        var id = CedarFilesystemResourceId.build(expected.id(), CedarResourceType.forValue(expected.type()));
        var node = folders.findResourceById(id);
        if (node == null || folders.findNodePathExtract(node).stream().noneMatch(p -> p.getId().equals(root))
            || !sameGraph(expected, entry(node, false)))
          return new Step(false, Reason.ITEM_CHANGED);
        if (node instanceof FolderServerFolder folder) {
          if (folder.isSystem() || folder.isRoot() || folder.isUserHome() ||
              !folders.deleteFolderById(id.asFolderId(), RevisionPreconditionParser.parse(expected.etag())))
            return new Step(false, Reason.FOLDER_CHANGED);
          removeIndexDocument(CedarUntypedFilesystemResourceId.build(expected.id()));
          return new Step(true, Reason.COMPLETED);
        }
        try (Response response = executeArtifactDelete(c, node.getType(), id.asArtifactId(), expected.etag())) {
          return response.getStatus() == 204 ? new Step(true, Reason.COMPLETED) : new Step(false,
              response.getStatus() == 202 ? Reason.CLEANUP_PENDING : Reason.ARTIFACT_REFUSED);
        }
      }
    }, c.getCedarUser().getId(), root);
  }
  private static boolean sameGraph(Entry a, Entry b) {
    return b != null && a.id().equals(b.id()) && a.type().equals(b.type()) && a.parentId().equals(b.parentId())
        && a.graphRevision() == b.graphRevision() && a.readable() == b.readable() && a.deletable() == b.deletable()
        && a.protectedFolder() == b.protectedFolder();
  }
  private static CedarPermission deletePermission(CedarResourceType type) {
    return switch (type) {
      case FOLDER -> CedarPermission.FOLDER_DELETE;
      case TEMPLATE -> CedarPermission.TEMPLATE_DELETE;
      case ELEMENT -> CedarPermission.TEMPLATE_ELEMENT_DELETE;
      case FIELD -> CedarPermission.TEMPLATE_FIELD_DELETE;
      case INSTANCE -> CedarPermission.TEMPLATE_INSTANCE_DELETE;
      default -> throw new IllegalArgumentException("Unsupported resource type");
    };
  }
}
