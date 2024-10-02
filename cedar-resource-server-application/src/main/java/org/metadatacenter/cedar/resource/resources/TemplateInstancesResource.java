package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.codec.CharEncoding;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;
import org.metadatacenter.artifacts.model.core.Artifact;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.artifacts.model.tools.YamlSerializer;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.constant.HttpConstants;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.id.CedarTemplateInstanceId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.proxy.ArtifactProxy;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.ProxyUtil;
import org.metadatacenter.util.json.JsonMapper;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

import static org.metadatacenter.constant.CedarPathParameters.PP_ID;
import static org.metadatacenter.constant.CedarQueryParameters.QP_FOLDER_ID;
import static org.metadatacenter.constant.CedarQueryParameters.QP_FORMAT;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/template-instances")
@Produces(MediaType.APPLICATION_JSON)
public class TemplateInstancesResource extends AbstractResourceServerResource {

  public TemplateInstancesResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  public Response createTemplateInstance(@QueryParam(QP_FOLDER_ID) Optional<String> folderId) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_CREATE);
    return executeResourceCreationOnArtifactServerAndGraphDb(c, CedarResourceType.INSTANCE, Optional.empty(), folderId);
  }

  @GET
  @Timed
  @Path("/{id}")
  public Response findTemplateInstance(@PathParam(PP_ID) String id, @QueryParam(QP_FORMAT) Optional<String> format) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    userMustHaveReadAccessToArtifact(c, tiid);
    return ArtifactProxy.executeResourceGetByProxyFromArtifactServer(microserviceUrlUtil, response, CedarResourceType.INSTANCE, id, format, c);
  }


  @POST
  @Timed
  @Path("/{id}/download")
  @Produces({MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML})
  public Response downloadTemplateInstance(@PathParam(PP_ID) String id,
                                           @HeaderParam("Accept") String acceptHeader,
                                           @QueryParam("compact") Optional<Boolean> compactParam) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    userMustHaveReadAccessToArtifact(c, tiid);

    String url = microserviceUrlUtil.getArtifact().getArtifactTypeWithId(CedarResourceType.INSTANCE, tiid);
    HttpResponse proxyResponse = ProxyUtil.proxyGet(url, c);
    // If error while retrieving artifact, re-run and return proxy call directly
    if (proxyResponse.getStatusLine().getStatusCode() != Response.Status.OK.getStatusCode()) {
      return executeResourceGetByProxyFromArtifactServer(CedarResourceType.INSTANCE, id, c);
    }
    HttpEntity entity = proxyResponse.getEntity();
    JsonNode instanceNode = null;

    try {
      String instanceSource = EntityUtils.toString(entity, CharEncoding.UTF_8);
      instanceNode = JsonMapper.MAPPER.readTree(instanceSource);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    String instanceUUID = linkedDataUtil.getUUID(id, CedarResourceType.INSTANCE);

    // Handle JSON
    if (acceptHeader == null || acceptHeader.isEmpty() || acceptHeader.contains(MediaType.APPLICATION_JSON) || acceptHeader.contains("*/*")) {
      String fileName = instanceUUID + ".json";
      return CedarResponse.ok()
          .type(MediaType.APPLICATION_JSON)
          .contentDispositionAttachment(fileName)
          .entity(instanceNode)
          .build();
    }
    // Handle YAML
    if (acceptHeader.contains(HttpConstants.CONTENT_TYPE_APPLICATION_YAML)) {
      String fileName = instanceUUID + ".yaml";
      JsonArtifactReader reader = new JsonArtifactReader();
      Artifact modelArtifact = reader.readTemplateInstanceArtifact((ObjectNode) instanceNode);
      String content = YamlSerializer.getYAML(modelArtifact, compactParam.isPresent() && compactParam.get());
      return CedarResponse.ok()
          .type(HttpConstants.CONTENT_TYPE_APPLICATION_YAML)
          .contentDispositionAttachment(fileName)
          .entity(content)
          .build();
    }
    // Unknown accept header
    return CedarResponse.badRequest()
        .errorMessage("You passed an invalid Accept header: '" + acceptHeader + "'")
        .errorKey(CedarErrorKey.INVALID_RESOURCE_TYPE)
        .parameter(HttpConstants.HTTP_HEADER_ACCEPT, acceptHeader)
        .parameter("allowed Accept headers", Arrays.toString(new String[]{MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML}))
        .build();
  }


  @GET
  @Timed
  @Path("/{id}/details")
  public Response findTemplateInstanceDetails(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    return getDetails(c, tiid);
  }

  @PUT
  @Timed
  @Path("/{id}")
  public Response updateTemplateInstance(@PathParam(PP_ID) String id, @QueryParam(QP_FOLDER_ID) Optional<String> folderId) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_UPDATE);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    return executeResourceCreateOrUpdateViaPut(c, CedarResourceType.INSTANCE, tiid, folderId);
  }

  @DELETE
  @Timed
  @Path("/{id}")
  public Response deleteTemplateInstance(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_DELETE);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    return executeArtifactDelete(c, CedarResourceType.INSTANCE, tiid);
  }

  @GET
  @Timed
  @Path("/{id}/permissions")
  public Response getTemplateInstancePermissions(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    return generateResourcePermissionsResponse(c, tiid);
  }

  @PUT
  @Timed
  @Path("/{id}/permissions")
  public Response updateTemplateInstancePermissions(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_UPDATE);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    return updateResourcePermissions(c, tiid);
  }

  @GET
  @Timed
  @Path("/{id}/report")
  public Response getTemplateInstanceReport(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    return generateArtifactReportResponse(c, tiid);
  }

}
