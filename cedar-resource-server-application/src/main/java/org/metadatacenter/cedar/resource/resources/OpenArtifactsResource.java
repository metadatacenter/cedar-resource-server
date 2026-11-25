package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.metadatacenter.cedar.resource.open.OpenFolderResponse;
import org.metadatacenter.cedar.util.dw.AnonymousAccess;
import org.metadatacenter.cedar.util.dw.CedarMicroserviceResource;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.CedarDependencyUnavailableException;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.id.CedarArtifactId;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.model.folderserver.extract.FolderServerResourceExtract;
import org.metadatacenter.model.request.NodeListRequest;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.util.NodeListUtil;
import org.metadatacenter.util.http.ArtifactServiceClient;
import org.metadatacenter.util.http.CedarError;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.LinkHeaderUtil;
import org.metadatacenter.util.http.PagedSortedTypedQuery;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.metadatacenter.constant.CedarPathParameters.PP_ID;
import static org.metadatacenter.constant.CedarQueryParameters.*;

/**
 * Resource owns every anonymous read decision, for artifacts and folder listings alike; OpenView
 * is only a compatibility adapter that relays these routes under its own URLs.
 */
@Path("/open")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Open artifacts")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "The artifact or folder is private, and so is every folder above it",
        content = @Content(schema = @Schema(implementation = CedarError.class))),
    @ApiResponse(responseCode = "404", description = "The artifact or folder does not exist",
        content = @Content(schema = @Schema(implementation = CedarError.class))),
    @ApiResponse(responseCode = "503", description = "A required backend is unavailable",
        content = @Content(schema = @Schema(implementation = CedarError.class)))
})
public class OpenArtifactsResource extends CedarMicroserviceResource {
  public OpenArtifactsResource(CedarConfig config) {
    super(config);
  }

  @GET @Timed @Path("/templates/{id}")
  @Operation(summary = "Read an open template anonymously", description = "User credentials never broaden access.")
  @ApiResponse(responseCode = "200", description = "The open artifact as JSON",
      content = @Content(schema = @Schema(implementation = org.metadatacenter.util.artifact.SchemaArtifactDocument.class)))
  public Response template(@PathParam(PP_ID) String id) throws CedarException {
    return readOpen(id, CedarResourceType.TEMPLATE);
  }

  @GET @Timed @Path("/template-elements/{id}")
  @Operation(summary = "Read an open element anonymously", description = "User credentials never broaden access.")
  @ApiResponse(responseCode = "200", description = "The open artifact as JSON",
      content = @Content(schema = @Schema(implementation = org.metadatacenter.util.artifact.SchemaArtifactDocument.class)))
  public Response element(@PathParam(PP_ID) String id) throws CedarException {
    return readOpen(id, CedarResourceType.ELEMENT);
  }

  @GET @Timed @Path("/template-fields/{id}")
  @Operation(summary = "Read an open field anonymously", description = "User credentials never broaden access.")
  @ApiResponse(responseCode = "200", description = "The open artifact as JSON",
      content = @Content(schema = @Schema(implementation = org.metadatacenter.util.artifact.SchemaArtifactDocument.class)))
  public Response field(@PathParam(PP_ID) String id) throws CedarException {
    return readOpen(id, CedarResourceType.FIELD);
  }

  @GET @Timed @Path("/template-instances/{id}")
  @Operation(summary = "Read an open instance anonymously", description = "User credentials never broaden access.")
  @ApiResponse(responseCode = "200", description = "The open artifact as JSON",
      content = @Content(schema = @Schema(implementation = org.metadatacenter.util.artifact.InstanceArtifactDocument.class)))
  public Response instance(@PathParam(PP_ID) String id) throws CedarException {
    return readOpen(id, CedarResourceType.INSTANCE);
  }

  @GET @Timed @Path("/folders/{id}")
  @AnonymousAccess
  @Operation(summary = "List the contents of an open folder anonymously",
      description = "Return a caller-independent public projection of what an open folder holds. A folder is "
          + "listed when it is open itself or when a folder above it is, and opening a folder makes its whole "
          + "subtree readable here. The breadcrumb carries ancestor names only. A child summary carries its "
          + "identifier, type and name, and for a field, element or template also its version and publication "
          + "status. No ACL, provenance, timestamp, DOI or caller-specific permission is returned. User "
          + "credentials never broaden access. Openness is checked before any parameter, so a private folder "
          + "answers 401 whatever the request asks for.")
  @ApiResponse(responseCode = "200", description = "One page of the folder's contents, and its path",
      content = @Content(schema = @Schema(ref = "#/components/schemas/OpenFolderResponse")))
  @ApiResponse(responseCode = "400", description = "A paging, sort or filter parameter is not valid",
      content = @Content(schema = @Schema(implementation = CedarError.class)))
  public Response folder(
      @Parameter(description = "Folder identifier: the bare identifier or the percent-encoded full IRI.",
          required = true)
      @PathParam(PP_ID) String id,
      @Parameter(description = "Comma-separated resource types to include, in place of all of them: "
          + "'folder', 'field', 'element', 'template', 'instance'.")
      @QueryParam(QP_RESOURCE_TYPES) Optional<String> resourceTypes,
      @Parameter(description = "Which versions of fields, elements and templates to include: 'latest', "
          + "'latest-published', 'latest-draft' or 'all'.")
      @QueryParam(QP_VERSION) Optional<String> versionParam,
      @Parameter(description = "Publication status of fields, elements and templates to include: "
          + "'bibo:draft', 'bibo:published' or 'all'.")
      @QueryParam(QP_PUBLICATION_STATUS) Optional<String> publicationStatusParam,
      @Parameter(description = "Comma-separated sort fields; a leading '-' reverses one.")
      @QueryParam(QP_SORT) Optional<String> sortParam,
      @Parameter(description = "How many entries to return, from 1 to 500. Defaults to 100.")
      @QueryParam(QP_LIMIT) Optional<Integer> limitParam,
      @Parameter(description = "How many entries to skip. Defaults to 0.")
      @QueryParam(QP_OFFSET) Optional<Integer> offsetParam) throws CedarException {
    CedarRequestContext anonymous = buildAnonymousRequestContext();
    String resolved = linkedDataUtil.resolveResourceId(CedarResourceType.FOLDER,
        id.contains("/") ? id : linkedDataUtil.getLinkedDataId(CedarResourceType.FOLDER, id));
    CedarFolderId folderId = CedarFolderId.build(resolved);
    FolderServiceSession folders = dataServices.getFolderServiceSession(anonymous);

    FolderServerFolder folder = folders.findFolderById(folderId);
    if (folder == null) {
      return CedarResponse.notFound()
          .id(folderId)
          .errorKey(CedarErrorKey.FOLDER_NOT_FOUND)
          .message("The folder can not be found")
          .build();
    }
    List<FolderServerResourceExtract> pathInfo = folders.findNodePathExtract(folder);
    boolean hasOpenAncestor = pathInfo.stream().anyMatch(segment -> Boolean.TRUE.equals(segment.getIsOpen()));
    if (!folder.isOpen() && !hasOpenAncestor) {
      return CedarResponse.unauthorized().id(folderId).build();
    }

    PagedSortedTypedQuery query = new PagedSortedTypedQuery(cedarConfig.getResourceRESTAPI().getPagination())
        .resourceTypes(resourceTypes)
        .version(versionParam)
        .publicationStatus(publicationStatusParam)
        .sort(sortParam)
        .limit(limitParam)
        .offset(offsetParam);
    query.queryParameters(uriInfo.getQueryParameters());
    query.validate();

    URI absoluteURI = uriInfo.getAbsolutePathBuilder()
        .queryParam(QP_RESOURCE_TYPES, query.getResourceTypesAsString())
        .queryParam(QP_VERSION, query.getVersionAsString())
        .queryParam(QP_PUBLICATION_STATUS, query.getPublicationStatusAsString())
        .queryParam(QP_SORT, query.getSortListAsString())
        .build();

    NodeListRequest request = NodeListUtil.buildNodeListRequest(query);
    List<FolderServerResourceExtract> resources = folders.findFolderContentsExtract(folderId, request);
    long total = folders.findFolderContentsCount(folderId, request);
    OpenFolderResponse body = new OpenFolderResponse(request, total,
        LinkHeaderUtil.getPagingLinkHeaders(absoluteURI.toString(), total, request.getLimit(), request.getOffset()),
        resources, pathInfo);
    // Openness can be revoked at any moment, so a listing is never served from a cache.
    return Response.ok().entity(body).header("Cache-Control", "no-store").build();
  }

  @AnonymousAccess
  private Response readOpen(String id, CedarResourceType type) throws CedarException {
    var anonymous = buildAnonymousRequestContext();
    String resolved = linkedDataUtil.resolveResourceId(type, id.contains("/") ? id : linkedDataUtil.getLinkedDataId(type, id));
    CedarArtifactId artifactId = CedarArtifactId.build(resolved, type);
    FolderServiceSession folders = dataServices.getFolderServiceSession(anonymous);
    FolderServerArtifact artifact = folders.findArtifactById(artifactId);
    if (artifact == null) return CedarResponse.notFound().id(artifactId).build();
    if (!artifact.isOpen() && !folders.isArtifactOpenImplicitly(artifactId)) {
      return CedarResponse.unauthorized().id(artifactId).build();
    }

    // Only after openness is proved: artifact still requires both the internal service key and a
    // backend user identity. Never substitute the HTTP caller's credential on this anonymous path.
    String url = microserviceUrlUtil.getArtifact().getArtifactTypeWithId(type, artifactId);
    try (ClassicHttpResponse upstream = new ArtifactServiceClient(cedarConfig).get(url,
        CedarRequestContextFactory.fromAdminUser(cedarConfig, dataServices.getNeoUserService()))) {
      Response.ResponseBuilder result = Response.status(upstream.getCode()).type(MediaType.APPLICATION_JSON);
      if (upstream.getEntity() != null) result.entity(EntityUtils.toByteArray(upstream.getEntity()));
      // Openness can be revoked independently of the document revision; do not serve cached bodies.
      return result.header("Cache-Control", "no-store").build();
    } catch (IOException e) {
      throw new CedarDependencyUnavailableException("Downstream service is unavailable", e);
    }
  }
}
