package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.cedar.deltafinder.DeltaFinder;
import org.metadatacenter.id.CedarTemplateId;
import org.metadatacenter.model.BiboStatus;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerSchemaArtifact;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.metadatacenter.util.http.CedarError;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.artifact.ArtifactServerUtil;
import org.metadatacenter.cedar.resource.model.InclusionSubgraphUpdateOutcome;
import org.metadatacenter.cedar.resource.model.InclusionSubgraphUpdateReport;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.error.CedarErrorPack;
import org.metadatacenter.exception.CedarBadRequestException;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.id.CedarTypedSchemaArtifactId;
import org.metadatacenter.id.CedarUntypedSchemaArtifactId;
import org.metadatacenter.model.request.inclusionsubgraph.InclusionSubgraphRequest;
import org.metadatacenter.model.request.inclusionsubgraph.InclusionSubgraphResponse;
import org.metadatacenter.model.request.inclusionsubgraph.InclusionSubgraphTodoElement;
import org.metadatacenter.model.request.inclusionsubgraph.InclusionSubgraphTodoList;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.InclusionSubgraphServiceSession;
import org.metadatacenter.server.cache.user.ProvenanceNameUtil;
import org.metadatacenter.server.search.util.InclusionSubgraphUtil;
import org.metadatacenter.util.CedarResourceTypeUtil;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/command")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Command")
@SecurityRequirement(name = "api_key")
public class CommandInclusionSubgraphResource extends AbstractResourceServerResource {

  private static final Logger log = LoggerFactory.getLogger(CommandInclusionSubgraphResource.class);

  public CommandInclusionSubgraphResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  @Path("/inclusions-subgraph-preview")
  @Operation(summary = "Preview the inclusion subgraph of an artifact", description = "Build a preview of the tree of artifacts affected by a change to the given artifact, without "
          + "applying any update.")
  @RequestBody(description = "The artifact that changed, and the elements and templates to consider", required = true,
      content = @Content(schema = @Schema(implementation = InclusionSubgraphRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The tree of affected artifacts",
          content = @Content(schema = @Schema(implementation = InclusionSubgraphResponse.class))),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response previewInclusionSubgraph() throws CedarException, IOException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    InclusionSubgraphRequest treeRequest = c.request().getRequestBody().convert(InclusionSubgraphRequest.class);

    normalizeSelectors(treeRequest);
    String id = treeRequest.getId();
    if (id == null) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.INVALID_DATA)
          .message("@id not provided for the inclusion subgraph request")
          .build();
    }
    CedarUntypedSchemaArtifactId aid = CedarUntypedSchemaArtifactId.build(id);

    userMustHaveCapabilityOnArtifact(c, aid, org.metadatacenter.server.security.model.permission.resource.ResourceCapability.READ_RESOURCE);

    InclusionSubgraphServiceSession inclusionSubgraphSession = dataServices.getInclusionSubgraphServiceSession(c);

    InclusionSubgraphResponse treeResponse = InclusionSubgraphUtil.buildAffectedTree(treeRequest, inclusionSubgraphSession);

    ProvenanceNameUtil.addProvenanceDisplayNames(treeResponse);

    return Response.ok(treeResponse).build();
  }

  @POST
  @Timed
  @Path("/inclusions-subgraph-update")
  @Operation(summary = "Update the inclusion subgraph of an artifact", description = "Propagate a change to the given artifact across the tree of affected artifacts, updating each "
          + "referencing artifact on the artifact server. The caller needs the readResource capability on the artifact "
          + "that changed and the updateResource capability on every artifact selected for update, and every artifact "
          + "selected for update must still be a draft, since publication fixes an artifact's content. If the caller "
          + "lacks either capability, or names a published target, the whole request is refused and nothing is "
          + "written. The response reports what became of each target.")
  @RequestBody(description = "The artifact that changed, and the elements and templates to consider", required = true,
      content = @Content(schema = @Schema(implementation = InclusionSubgraphRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Every target was written, or needed no write",
          content = @Content(schema = @Schema(implementation = InclusionSubgraphUpdateReport.class))),
      @ApiResponse(responseCode = "400", description = "The body is not JSON in the form of an inclusion subgraph "
          + "request (malformedJsonRequestBody), names no @id or a target that is not an artifact (invalidData), or "
          + "names one artifact twice. A target that is published (publishedArtifactCanNotBeChanged), or a template "
          + "whose instances the change would invalidate (invalidData), is refused too. Nothing was written.",
          content = @Content(schema = @Schema(implementation = CedarError.class))),
      @ApiResponse(responseCode = "401", description = UNAUTHORIZED,
          content = @Content(schema = @Schema(implementation = CedarError.class))),
      @ApiResponse(responseCode = "403", description = "The caller cannot read the artifact that changed, or cannot "
          + "update a target. Nothing was written.",
          content = @Content(schema = @Schema(implementation = CedarError.class))),
      @ApiResponse(responseCode = "404", description = "The artifact that changed, or a target, does not exist",
          content = @Content(schema = @Schema(implementation = CedarError.class))),
      @ApiResponse(responseCode = "406", description = NOT_ACCEPTABLE,
          content = @Content(schema = @Schema(implementation = CedarError.class))),
      @ApiResponse(responseCode = "429", description = RATE_LIMITED,
          content = @Content(schema = @Schema(implementation = CedarError.class))),
      @ApiResponse(responseCode = "500", description = INTERNAL_ERROR,
          content = @Content(schema = @Schema(implementation = CedarError.class))),
      @ApiResponse(responseCode = "502", description = "The artifact server refused at least one of the writes "
          + "(upstreamServerError). The targets it accepted were updated. The body's objects.report holds the update "
          + "report, with the outcome of every target.",
          content = @Content(schema = @Schema(implementation = CedarError.class))),
      @ApiResponse(responseCode = "503", description = UNAVAILABLE,
          content = @Content(schema = @Schema(implementation = CedarError.class)))
  })
  public Response updateInclusionSubgraph() throws CedarException, IOException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    InclusionSubgraphRequest treeRequest = c.request().getRequestBody().convert(InclusionSubgraphRequest.class);

    normalizeSelectors(treeRequest);
    String id = treeRequest.getId();
    if (id == null) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.INVALID_DATA)
          .message("@id not provided for the inclusion subgraph request")
          .build();
    }
    CedarUntypedSchemaArtifactId aid = CedarUntypedSchemaArtifactId.build(id);

    userMustHaveCapabilityOnArtifact(c, aid, org.metadatacenter.server.security.model.permission.resource.ResourceCapability.READ_RESOURCE);

    InclusionSubgraphServiceSession inclusionSubgraphSession = dataServices.getInclusionSubgraphServiceSession(c);

    InclusionSubgraphResponse treeResponse = InclusionSubgraphUtil.buildAffectedTree(treeRequest, inclusionSubgraphSession);

    InclusionSubgraphTodoList todoList = InclusionSubgraphUtil.updateResources(treeResponse);

    // Every target is admitted before any target is written. Propagation is not transactional, so a
    // check interleaved with the writes would leave the tree half-propagated on the first refusal, and a
    // partly propagated tree is worse to unpick than one that was never touched.
    FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);
    for (InclusionSubgraphTodoElement todo : todoList.getTodoList()) {
      CedarTypedSchemaArtifactId targetArtifactId = CedarResourceTypeUtil.buildTypedArtifactId(todo.getTargetId());
      if (targetArtifactId == null) {
        return CedarResponse.badRequest()
            .errorKey(CedarErrorKey.INVALID_DATA)
            .message("The target is not an artifact id: " + todo.getTargetId())
            .build();
      }
      userMustHaveCapabilityOnArtifact(c, targetArtifactId, org.metadatacenter.server.security.model.permission.resource.ResourceCapability.UPDATE_RESOURCE);

      // Publication fixes an artifact's content, and propagation writes that content. The check the
      // ordinary artifact PUT makes is on that path alone, and these writes go straight to the artifact
      // server, so a published target would otherwise be rewritten under the version it was released as.
      FolderServerArtifact target = folderSession.findArtifactById(targetArtifactId);
      if (target instanceof FolderServerSchemaArtifact schemaArtifact
          && schemaArtifact.getPublicationStatus() == BiboStatus.PUBLISHED) {
        return CedarResponse.badRequest()
            .errorKey(CedarErrorKey.PUBLISHED_ARTIFACT_CAN_NOT_BE_CHANGED)
            .message("Cannot propagate this change into a published artifact. Create a new version of it "
                + "before applying the updated element or field. No propagation targets were updated.")
            .parameter("name", target.getName())
            .parameter("targetId", todo.getTargetId())
            .build();
      }
    }

    List<InclusionSubgraphUpdateOutcome> outcomes = new ArrayList<>();
    List<PreparedUpdate> prepared = new ArrayList<>();
    Map<String, JsonNode> proposed = new HashMap<>();
    for (InclusionSubgraphTodoElement todo : todoList.getTodoList()) {
      CedarTypedSchemaArtifactId sourceArtifactId = CedarResourceTypeUtil.buildTypedArtifactId(todo.getSourceId());
      CedarTypedSchemaArtifactId targetArtifactId = CedarResourceTypeUtil.buildTypedArtifactId(todo.getTargetId());

      String sourceArtifact = ArtifactServerUtil.getSchemaArtifactFromArtifactServer(sourceArtifactId.getType(), sourceArtifactId, c, cedarConfig, null);
      var targetArtifactContent = ArtifactServerUtil.getSchemaArtifactWithEtagFromArtifactServer(
          targetArtifactId.getType(), targetArtifactId, c, cedarConfig, null);
      String targetArtifact = targetArtifactContent.content();
      JsonNode sourceJsonNode = proposed.containsKey(todo.getSourceId())
          ? proposed.get(todo.getSourceId()).deepCopy() : JsonMapper.STRICT_MAPPER.readTree(sourceArtifact);
      JsonNode storedTarget = JsonMapper.STRICT_MAPPER.readTree(targetArtifact);
      // Publication may commit after graph preflight. This body and its ETag are one snapshot;
      // a later publication changes that ETag and is refused by the conditional write below.
      if (BiboStatus.PUBLISHED.getValue().equals(storedTarget.path("bibo:status").asText())) {
        return CedarResponse.badRequest().errorKey(CedarErrorKey.PUBLISHED_ARTIFACT_CAN_NOT_BE_CHANGED)
            .message("A propagation target was published. No propagation targets were updated.")
            .parameter("targetId", todo.getTargetId()).build();
      }
      JsonNode targetJsonNode = proposed.containsKey(todo.getTargetId())
          ? proposed.get(todo.getTargetId()).deepCopy() : storedTarget.deepCopy();

      // The graph said the target includes the source, but the content is what gets written. When the
      // two disagree, writing the target back unchanged would bump its provenance for no change at all.
      if (!InclusionSubgraphUtil.updateSubdocumentByAtId(targetJsonNode, todo.getSourceId(), sourceJsonNode)) {
        log.warn("Artifact {} does not contain {}, though the graph says it includes it; nothing was written",
            todo.getTargetId(), todo.getSourceId());
        outcomes.add(InclusionSubgraphUpdateOutcome.unchanged(todo.getSourceId(), todo.getTargetId()));
        continue;
      }
      if (targetArtifactId.getType() == CedarResourceType.TEMPLATE) {
        long instances = folderSession.getNumberOfInstances(CedarTemplateId.build(todo.getTargetId()));
        if (requiresNewVersion(instances, storedTarget, targetJsonNode)) {
          return CedarResponse.badRequest()
              .errorKey(CedarErrorKey.INVALID_DATA)
              .message("Cannot propagate this change into a template with existing instances. "
                  + "Create a new template version before applying the updated element or field. "
                  + "No propagation targets were updated.")
              .parameter("templateId", todo.getTargetId())
              .parameter("numberOfInstances", instances)
              .build();
        }
      }
      proposed.put(todo.getTargetId(), targetJsonNode);
      boolean structuralTemplateChange = targetArtifactId.getType() == CedarResourceType.TEMPLATE
          && requiresNewVersion(1, storedTarget, targetJsonNode);
      prepared.add(new PreparedUpdate(todo, targetArtifactId, targetJsonNode, targetArtifactContent.etag(), structuralTemplateChange, targetArtifactContent.content()));
    }

    // Preflight the complete proposed graph before the first write, including changes through elements.
    for (PreparedUpdate update : prepared) {
      InclusionSubgraphTodoElement todo = update.todo();
      CedarTypedSchemaArtifactId targetArtifactId = update.targetId();
      JsonNode targetJsonNode = update.document();
      String newTargetContent = JsonMapper.STRICT_MAPPER.writeValueAsString(targetJsonNode);

      Response putResponse = ArtifactServerUtil.putSchemaArtifactToArtifactServer(targetArtifactId.getType(),
          targetArtifactId, c, newTargetContent, cedarConfig, update.etag(), update.requireNoInstances());
      int putStatus = putResponse.getStatus();
      if (putStatus >= 400) {
        log.error("The artifact server refused the propagation of {} into {} with status {}",
            todo.getSourceId(), todo.getTargetId(), putStatus);
        outcomes.add(InclusionSubgraphUpdateOutcome.failed(todo.getSourceId(), todo.getTargetId(), putStatus,
            putResponse.getEntity() instanceof JsonNode answer ? answer : null));
        continue;
      }

      applyArtifactUpdateSideEffects(c, targetArtifactId, targetJsonNode, update.preImage(),
          putResponse.getHeaderString(jakarta.ws.rs.core.HttpHeaders.ETAG));
      outcomes.add(InclusionSubgraphUpdateOutcome.updated(todo.getSourceId(), todo.getTargetId(), putStatus));
    }

    ProvenanceNameUtil.addProvenanceDisplayNames(treeResponse);

    InclusionSubgraphUpdateReport report = new InclusionSubgraphUpdateReport(treeResponse, outcomes);
    if (report.isComplete()) {
      return Response.ok(report).build();
    }
    // A refused write is the artifact server's answer, not the caller's mistake, so it reports as a bad
    // gateway. The shared error body carries the report, as it carries a validation refusal's findings,
    // and the report's per-target outcomes say which writes landed.
    long refused = outcomes.stream()
        .filter(outcome -> outcome.getStatus() == InclusionSubgraphUpdateOutcome.Status.FAILED).count();
    return CedarResponse.badGateway()
        .errorKey(CedarErrorKey.UPSTREAM_SERVER_ERROR)
        .message("The artifact server refused " + refused + " of " + prepared.size() + " propagation writes. "
            + "The report lists what became of each target.")
        .object("report", report)
        .build();
  }

  private static final String UNAUTHORIZED = "The request carries no valid credentials";
  private static final String NOT_ACCEPTABLE = "The request does not accept a JSON response";
  private static final String RATE_LIMITED = "The deployment limits each user's request rate, and the caller "
      + "exceeded it";
  private static final String INTERNAL_ERROR = "Internal server error";
  private static final String UNAVAILABLE = "The graph database, the artifact server or another service the "
      + "request depends on is unavailable";

  static boolean requiresNewVersion(long instances, JsonNode stored, JsonNode proposed) {
    if (instances == 0) return false;
    JsonArtifactReader reader = new JsonArtifactReader();
    var delta = new DeltaFinder().findDelta(
        reader.readTemplateSchemaArtifact((ObjectNode) stored),
        reader.readTemplateSchemaArtifact((ObjectNode) proposed));
    return !delta.getDestructiveChanges().isEmpty() || !delta.getNonDestructiveChanges().isEmpty();
  }

  private void normalizeSelectors(InclusionSubgraphRequest request) throws CedarBadRequestException {
    if (request.getId() != null) request.setId(linkedDataUtil.resolveResourceId(request.getId()));
    request.setElements(normalizeElements(request.getElements()));
    request.setTemplates(normalizeKeys(request.getTemplates(), CedarResourceType.TEMPLATE));
  }

  private Map<String, org.metadatacenter.model.request.inclusionsubgraph.InclusionSubgraphElement> normalizeElements(
      Map<String, org.metadatacenter.model.request.inclusionsubgraph.InclusionSubgraphElement> elements)
      throws CedarBadRequestException {
    var resolved = normalizeKeys(elements, CedarResourceType.ELEMENT);
    if (resolved != null) for (var element : resolved.values()) {
      if (element != null) {
        element.setElements(normalizeElements(element.getElements()));
        element.setTemplates(normalizeKeys(element.getTemplates(), CedarResourceType.TEMPLATE));
      }
    }
    return resolved;
  }

  /**
   * The selectors keyed by the identifiers they resolve to. Two selectors that name one artifact are
   * refused in the shared error body, with the message naming the second. A BadRequestException
   * without an entity reached the client as a 400 whose body carried no message.
   */
  private <T> Map<String, T> normalizeKeys(Map<String, T> selectors, CedarResourceType type)
      throws CedarBadRequestException {
    if (selectors == null) return null;
    Map<String, T> resolved = new java.util.LinkedHashMap<>();
    for (Map.Entry<String, T> selector : selectors.entrySet()) {
      String id = linkedDataUtil.resolveResourceId(type, selector.getKey());
      if (resolved.containsKey(id)) {
        throw new CedarBadRequestException(new CedarErrorPack()
            .message("Duplicate resource selector: " + selector.getKey()));
      }
      resolved.put(id, selector.getValue());
    }
    return resolved;
  }

  private record PreparedUpdate(InclusionSubgraphTodoElement todo, CedarTypedSchemaArtifactId targetId,
                                JsonNode document, String etag, boolean requireNoInstances, String preImage) { }
}
