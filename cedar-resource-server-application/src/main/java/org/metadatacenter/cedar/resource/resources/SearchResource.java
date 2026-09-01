package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Optional;

import static org.metadatacenter.constant.CedarQueryParameters.*;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Search")
@SecurityRequirement(name = "api_key")
public class SearchResource extends AbstractSearchResource {

  public SearchResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @GET
  @Timed
  @Path("/search")
  @Operation(summary = "Search resources", description = "Search resources using different criteria. All of the parameters are optional, but you need to "
          + "provide at least one search criteria. The parameters can be combined, but not all of the "
          + "combinations will work. You can see the type of the executed search in the response body.", tags = {"Search", "Template Fields", "Template Elements", "Templates", "Template Instances", "Folders",
          "Folder Contents", "Versioning"})
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response search(
      @Parameter(description = "Search term. It will be looked up in the artifact names")
      @QueryParam(QP_Q) Optional<String> q,
      @Parameter(description = "Artifact id. If passed, only the artifact or folder with the given id will be returned.")
      @QueryParam(QP_ID) Optional<String> id,
      @Parameter(description = "Resource types as comma separated values. The allowed values are: 'folder', 'field', "
          + "'element', 'template', 'instance'")
      @QueryParam(QP_RESOURCE_TYPES) Optional<String> resourceTypes,
      @Parameter(description = "Version selector. It is only handled for template-fields, template-elements and "
          + "templates. The allowed values are: 'latest', 'all'")
      @QueryParam(QP_VERSION) Optional<String> versionParam,
      @Parameter(description = "Publication status selector. It is only handled for template-fields, template-elements "
          + "and templates. The allowed values are: 'bibo:draft', 'bibo:published', 'all'")
      @QueryParam(QP_PUBLICATION_STATUS) Optional<String> publicationStatusParam,
      @Parameter(description = "Template identifier. All the instances with this template id will be returned")
      @QueryParam(QP_IS_BASED_ON) Optional<String> isBasedOnParam,
      @Parameter(description = "Sort field names as comma separated values. Prepending a field with '-' means descending "
          + "order on that field. The allowed values are: 'name', 'lastUpdatedOnTS', 'createdOnTS'")
      @QueryParam(QP_SORT) Optional<String> sortParam,
      @Parameter(description = "Paging limit")
      @QueryParam(QP_LIMIT) Optional<Integer> limitParam,
      @Parameter(description = "Paging offset")
      @QueryParam(QP_OFFSET) Optional<Integer> offsetParam,
      @Parameter(description = "Sharing modifier for the search. Only the artifacts and folder matching the criteria will "
          + "be returned.")
      @QueryParam(QP_SHARING) Optional<String> sharing,
      @Parameter(description = "Search mode. The only value currently supported is 'special-folders'. If passed, the "
          + "list of special folders will be returned ('/Shared', etc.)")
      @QueryParam(QP_MODE) Optional<String> mode,
      @Parameter(description = "Category Id. All the artifacts in the given category will be returned.")
      @QueryParam(QP_CATEGORY_ID) Optional<String> categoryIdParam,
      @Parameter(description = "Continuation from a previous answer. Only /search-deep serves one; this route "
          + "refuses it rather than answering the first page as though the caller had never asked to carry on.")
      @QueryParam(QP_CONTINUATION) Optional<String> continuationParam) throws CedarException {

    return super.search(q, id, resourceTypes, versionParam, publicationStatusParam, isBasedOnParam, sortParam,
        limitParam, offsetParam, sharing, mode, categoryIdParam, continuationParam, false);
  }
}
