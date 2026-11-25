package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.metadatacenter.cedar.resource.search.JobStatus;
import org.metadatacenter.cedar.resource.search.Jobs;
import org.metadatacenter.cedar.resource.security.AdminCommand;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.util.http.CedarError;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.PagedQuery;
import org.metadatacenter.util.json.JsonMapper;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.metadatacenter.constant.CedarPathParameters.PP_ID;
import static org.metadatacenter.constant.CedarQueryParameters.QP_KIND;
import static org.metadatacenter.constant.CedarQueryParameters.QP_LIMIT;
import static org.metadatacenter.constant.CedarQueryParameters.QP_OFFSET;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

/**
 * The jobs this server runs: search index rebuilds and value sets ontology imports.
 *
 * <p>A command that starts a job answers 202 with a Location naming the job here. Jobs are held in
 * memory, so the collection holds what this process has run, within a window of the most recent, and
 * the rebuild a restart interrupted.
 */
@Path("/jobs")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Jobs")
@SecurityRequirement(name = "api_key")
public class JobsResource extends AbstractResourceServerResource {

  private static final int DEFAULT_PAGE_SIZE = 100;
  private static final int MAX_PAGE_SIZE = 500;
  private static final List<String> KINDS = List.of(JobStatus.SEARCH_INDEX, JobStatus.VALUE_SETS_IMPORT);

  public JobsResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  /** Where a caller polls one job, whether it started the job or was refused by it. */
  public static URI jobUri(String jobId) {
    return URI.create("/jobs/" + jobId);
  }

  @GET
  @Timed
  @Operation(summary = "List the jobs this server knows",
      description = "List the search index rebuilds and value sets ontology imports this server still knows, "
          + "newest first, so the first job of a kind is the one running now or the one that ran last. "
          + "Jobs are held in memory: the listing holds the most recent twenty of each kind since the last "
          + "restart, and the rebuild that restart interrupted.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "One page of jobs",
          content = @Content(schema = @Schema(ref = "#/components/schemas/JobPage"))),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The kind is not one this server runs, or the limit or offset is out of range"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response listJobs(
      @Parameter(description = "Return only jobs of this kind: search-index or value-sets-import. Omit it for both.")
      @QueryParam(QP_KIND) Optional<String> kind,
      @Parameter(description = "How many jobs to return, from 1 to " + MAX_PAGE_SIZE + ". Defaults to "
          + DEFAULT_PAGE_SIZE + ".")
      @QueryParam(QP_LIMIT) Optional<Integer> limit,
      @Parameter(description = "How many jobs to skip. Defaults to 0.")
      @QueryParam(QP_OFFSET) Optional<Integer> offset) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    PagedQuery page = new PagedQuery(DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE).limit(limit).offset(offset)
        .queryParameters(uriInfo.getQueryParameters());
    page.validate();
    if (kind.isPresent() && !KINDS.contains(kind.get())) {
      return CedarResponse.badRequest()
          .message("This server runs no job of kind " + kind.get() + ". It runs " + String.join(" and ", KINDS))
          .parameter(QP_KIND, kind.get())
          .build();
    }

    List<JobStatus> jobs = Jobs.list(kind);
    List<JobStatus> shown = jobs.subList(Math.min(page.getOffset(), jobs.size()),
        Math.min(page.getOffset() + page.getLimit(), jobs.size()));
    JobPage body = new JobPage(List.copyOf(shown), uriInfo.getRequestUri().toString(), jobs.size(),
        page.getLimit(), page.getOffset());
    return Response.ok().entity(JsonMapper.STRICT_MAPPER.valueToTree(body)).build();
  }

  @GET
  @Timed
  @Path("/{id}")
  @Operation(summary = "Get the status of one job",
      description = "Report what became of the job named by the identifier its command returned, of whatever "
          + "kind. Jobs are held in memory, so an identifier from before the last restart, or one that twenty "
          + "later jobs of its kind have pushed out, is no longer known. The exception is a rebuild the "
          + "restart interrupted, which answers as INTERRUPTED.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The job and its current state",
          content = @Content(schema = @Schema(ref = "#/components/schemas/JobStatus"))),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "No job answers to this identifier"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response getJob(
      @Parameter(description = "Job identifier, as returned when the job was queued.", required = true)
      @PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    return Jobs.find(id)
        .map(job -> Response.ok().entity(JsonMapper.STRICT_MAPPER.valueToTree(job)).build())
        .orElseGet(() -> noSuchJob(id));
  }

  @POST
  @Timed
  @Path("/{id}/reset")
  @Operation(summary = "Take back the claim of a job that stopped reporting progress",
      description = "Only one rebuild of an index, and one value sets import, runs at a time. A job that never "
          + "returns would otherwise hold its claim until the server restarted, refusing every later command "
          + "with a conflict naming a job that is not running. Once a rebuild has reported no progress for an "
          + "hour, or an import has run for six, the status reports it as overdue and this takes its claim "
          + "back, so the next command can run. It does not stop the abandoned job, which nothing can, so run "
          + "it only once the log shows that job has stopped making progress. A job within its deadline is "
          + "left alone and answers with a conflict, as does one that has finished.",
      tags = {"Jobs", "Administration"})
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The claim was taken back, and the job is ABANDONED",
          content = @Content(schema = @Schema(ref = "#/components/schemas/JobStatus"))),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "No job answers to this identifier"),
      @ApiResponse(responseCode = "409", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The job has finished, or is running within its deadline"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response resetJob(
      @Parameter(description = "Job identifier, as returned when the job was queued.", required = true)
      @PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    AdminCommand.RESET_JOB.enforce(c);

    Optional<JobStatus> job = Jobs.find(id);
    if (job.isEmpty()) {
      return noSuchJob(id);
    }
    Jobs.ResetOutcome outcome = Jobs.reset(job.get());
    JobStatus now = Jobs.find(id).orElse(job.get());
    return switch (outcome) {
      case RESET -> Response.ok().entity(JsonMapper.STRICT_MAPPER.valueToTree(now)).build();
      case NOT_RUNNING -> CedarResponse.conflict()
          .message("Nothing to reset: job " + id + " is " + now.state().name().toLowerCase(Locale.ROOT)
              + " and holds no claim")
          .parameter("jobId", id)
          .parameter("state", now.state().name())
          .build();
      case WITHIN_DEADLINE -> CedarResponse.conflict()
          .message("Nothing to reset: job " + id + " is running and still within its deadline; it would be "
              + "believed stopped at " + now.deadlineAt())
          .parameter("jobId", id)
          .parameter("state", now.state().name())
          .parameter("deadlineAt", now.deadlineAt())
          .build();
    };
  }

  /**
   * Nothing answers to this identifier. Said as a 404 rather than as an empty status, because a
   * caller polling a job it started needs to tell "not finished yet" from "no longer known". The
   * first is worth waiting on, and the second never resolves.
   */
  private static Response noSuchJob(String jobId) {
    return CedarResponse.notFound()
        .message("No job answers to " + jobId
            + ". Jobs are held in memory, so an identifier from before the last restart, or one that later "
            + "jobs have pushed out, is no longer known")
        .parameter("jobId", jobId)
        .build();
  }
}
