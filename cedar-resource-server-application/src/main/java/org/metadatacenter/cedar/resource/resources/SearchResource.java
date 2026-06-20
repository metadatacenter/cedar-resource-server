package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Authorization;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Optional;

import static org.metadatacenter.constant.CedarQueryParameters.*;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Api(value = "/", tags = "Search", authorizations = {@Authorization("api_key")})
public class SearchResource extends AbstractSearchResource {

  public SearchResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @GET
  @Timed
  @Path("/search")
  @ApiOperation(value = "Search resources",
      notes = "Search resources using different criteria. All of the parameters are optional, but you need to "
          + "provide at least one search criteria. The parameters can be combined, but not all of the "
          + "combinations will work. You can see the type of the executed search in the response body.",
      tags = {"Search", "Template Fields", "Template Elements", "Templates", "Template Instances", "Folders",
          "Folder Contents", "Versioning"})
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response search(
      @ApiParam(value = "Search term. It will be looked up in the artifact names")
      @QueryParam(QP_Q) Optional<String> q,
      @ApiParam(value = "Artifact id. If passed, only the artifact or folder with the given id will be returned.")
      @QueryParam(QP_ID) Optional<String> id,
      @ApiParam(value = "Resource types as comma separated values. The allowed values are: 'folder', 'field', "
          + "'element', 'template', 'instance'")
      @QueryParam(QP_RESOURCE_TYPES) Optional<String> resourceTypes,
      @ApiParam(value = "Version selector. It is only handled for template-fields, template-elements and "
          + "templates. The allowed values are: 'latest', 'all'")
      @QueryParam(QP_VERSION) Optional<String> versionParam,
      @ApiParam(value = "Publication status selector. It is only handled for template-fields, template-elements "
          + "and templates. The allowed values are: 'bibo:draft', 'bibo:published', 'all'")
      @QueryParam(QP_PUBLICATION_STATUS) Optional<String> publicationStatusParam,
      @ApiParam(value = "Template identifier. All the instances with this template id will be returned")
      @QueryParam(QP_IS_BASED_ON) Optional<String> isBasedOnParam,
      @ApiParam(value = "Sort field names as comma separated values. Prepending a field with '-' means descending "
          + "order on that field. The allowed values are: 'name', 'lastUpdatedOnTS', 'createdOnTS'")
      @QueryParam(QP_SORT) Optional<String> sortParam,
      @ApiParam(value = "Paging limit")
      @QueryParam(QP_LIMIT) Optional<Integer> limitParam,
      @ApiParam(value = "Paging offset")
      @QueryParam(QP_OFFSET) Optional<Integer> offsetParam,
      @ApiParam(value = "Sharing modifier for the search. Only the artifacts and folder matching the criteria will "
          + "be returned.")
      @QueryParam(QP_SHARING) Optional<String> sharing,
      @ApiParam(value = "Search mode. The only value currently supported is 'special-folders'. If passed, the "
          + "list of special folders will be returned ('/Shared', etc.)")
      @QueryParam(QP_MODE) Optional<String> mode,
      @ApiParam(value = "Category Id. All the artifacts in the given category will be returned.")
      @QueryParam(QP_CATEGORY_ID) Optional<String> categoryIdParam) throws CedarException {

    return super.search(q, id, resourceTypes, versionParam, publicationStatusParam, isBasedOnParam, sortParam,
        limitParam, offsetParam, sharing, mode, categoryIdParam, false);
  }
}
