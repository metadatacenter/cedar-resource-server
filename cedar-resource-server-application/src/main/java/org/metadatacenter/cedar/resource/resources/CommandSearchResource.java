package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import org.metadatacenter.cedar.resource.search.ValueSetsImportStatusManager;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.assertion.noun.CedarRequestBody;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.search.util.GenerateEmptyRulesIndexTask;
import org.metadatacenter.server.search.util.GenerateEmptySearchIndexTask;
import org.metadatacenter.server.search.util.LoadValueSetsOntologyTask;
import org.metadatacenter.server.search.util.RegenerateRulesIndexTask;
import org.metadatacenter.server.search.util.RegenerateSearchIndexTask;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.server.service.UserService;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/command")
@Produces(MediaType.APPLICATION_JSON)
public class CommandSearchResource extends AbstractResourceServerResource {

  private static final Logger log = LoggerFactory.getLogger(CommandSearchResource.class);
  private static UserService userService;

  public CommandSearchResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  public static void injectUserService(UserService us) {
    userService = us;
  }

  @POST
  @Timed
  @Path("/load-valuesets-ontology")
  public Response loadValueSetsOntology() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    //c.must(c.user()).have(CedarPermission.SEARCH_INDEX_REINDEX);

    if (ValueSetsImportStatusManager.getInstance().getImportStatus() == ValueSetsImportStatusManager.ImportStatus.IN_PROGRESS) {
      return CedarResponse.badRequest().errorMessage("Value set loading already in progress").build();
    } else {
      ValueSetsImportStatusManager.getInstance().setImportStatus(ValueSetsImportStatusManager.ImportStatus.IN_PROGRESS);

      ExecutorService executor = Executors.newSingleThreadExecutor();
      executor.submit(() -> {
        LoadValueSetsOntologyTask task = new LoadValueSetsOntologyTask(cedarConfig);
        try {
          CedarRequestContext cedarAdminRequestContext = CedarRequestContextFactory.fromAdminUser(cedarConfig, userService);

          task.loadValueSetsOntology(cedarAdminRequestContext);

          ValueSetsImportStatusManager.getInstance().setImportStatus(ValueSetsImportStatusManager.ImportStatus.COMPLETE);
        } catch (CedarProcessingException e) {
          ValueSetsImportStatusManager.getInstance().setImportStatus(ValueSetsImportStatusManager.ImportStatus.ERROR);
          log.error("Error in load value sets ontology executor", e);
        }
      });
      return Response.ok().build();
    }
  }

  @GET
  @Timed
  @Path("/load-valuesets-ontology-status")
  public Response loadValueSetsOntologyStatus() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    JsonNode output = JsonMapper.MAPPER.valueToTree(ValueSetsImportStatusManager.getInstance());
    return Response.ok().entity(output).build();
  }

  @POST
  @Timed
  @Path("/regenerate-search-index")
  public Response regenerateSearchIndex() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.SEARCH_INDEX_REINDEX);

    CedarRequestBody requestBody = c.request().getRequestBody();
    CedarParameter forceParam = requestBody.get("force");
    final boolean force = forceParam.booleanValue();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executor.submit(() -> {
      RegenerateSearchIndexTask task = new RegenerateSearchIndexTask(cedarConfig);
      try {
        CedarRequestContext cedarAdminRequestContext = CedarRequestContextFactory.fromAdminUser(cedarConfig, userService);
        task.regenerateSearchIndex(force, cedarAdminRequestContext);
      } catch (CedarProcessingException e) {
        //TODO: handle this, log it separately
        log.error("Error in index regeneration executor", e);
      }
    });

    return Response.ok().build();
  }

  @POST
  @Timed
  @Path("/generate-empty-search-index")
  public Response generateEmptySearchIndex() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.SEARCH_INDEX_REINDEX);

    ExecutorService executor = Executors.newSingleThreadExecutor();
    executor.submit(() -> {
      GenerateEmptySearchIndexTask task = new GenerateEmptySearchIndexTask(cedarConfig);
      try {
        CedarRequestContext cedarAdminRequestContext = CedarRequestContextFactory.fromAdminUser(cedarConfig, userService);
        task.generateEmptySearchIndex(cedarAdminRequestContext);
      } catch (CedarProcessingException e) {
        //TODO: handle this, log it separately
        log.error("Error in index regeneration executor", e);
      }
    });

    return Response.ok().build();
  }

  @POST
  @Timed
  @Path("/regenerate-rules-index")
  public Response regenerateRulesIndex() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.RULES_INDEX_REINDEX);

    CedarRequestBody requestBody = c.request().getRequestBody();
    CedarParameter forceParam = requestBody.get("force");
    final boolean force = forceParam.booleanValue();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executor.submit(() -> {
      RegenerateRulesIndexTask task = new RegenerateRulesIndexTask(cedarConfig);
      try {
        CedarRequestContext cedarAdminRequestContext = CedarRequestContextFactory.fromAdminUser(cedarConfig, userService);
        task.regenerateRulesIndex(force, cedarAdminRequestContext);
      } catch (CedarProcessingException e) {
        //TODO: handle this, log it separately
        log.error("Error in index regeneration executor", e);
      }
    });

    return Response.ok().build();
  }

  @POST
  @Timed
  @Path("/generate-empty-rules-index")
  public Response generateEmptyRulesIndex() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.SEARCH_INDEX_REINDEX);

    ExecutorService executor = Executors.newSingleThreadExecutor();
    executor.submit(() -> {
      GenerateEmptyRulesIndexTask task = new GenerateEmptyRulesIndexTask(cedarConfig);
      try {
        CedarRequestContext cedarAdminRequestContext = CedarRequestContextFactory.fromAdminUser(cedarConfig, userService);
        task.generateEmptyRulesIndex(cedarAdminRequestContext);
      } catch (CedarProcessingException e) {
        //TODO: handle this, log it separately
        log.error("Error in index regeneration executor", e);
      }
    });

    return Response.ok().build();
  }

}
