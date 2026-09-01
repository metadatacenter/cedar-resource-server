package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.cedar.resource.search.IndexJobGuard;
import org.metadatacenter.cedar.resource.search.JobClaim;
import org.metadatacenter.cedar.resource.search.ValueSetsImportStatusManager;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.server.search.elasticsearch.service.NoOpNodeIndexingService;
import org.metadatacenter.server.search.permission.SearchPermissionEnqueueService;
import org.metadatacenter.server.search.util.IndexUtils;
import org.metadatacenter.server.valuerecommender.ValuerecommenderReindexQueueService;
import org.metadatacenter.util.json.JsonMapper;
import org.metadatacenter.util.test.EmbeddedCedarNeo4j;
import org.metadatacenter.util.test.TestAuthUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an administrative command answers for work it has only queued, and how a caller follows that
 * work afterwards.
 *
 * <p>The five commands answered 200 the moment they handed the job to a thread, which says the
 * rebuild is done — the one thing the response cannot mean. They also returned nothing to ask after:
 * the status routes reported whichever job had run last over an index, so a caller could not tell its
 * own rebuild from the next one, and had no way to learn that its own had failed. Both halves are
 * asserted here, on the queued 202 and on the 409 that refuses a second job over a busy index.
 *
 * <p>Only the value sets import is actually queued. Its background half reads a file path that no
 * test configures, so it fails immediately and touches nothing, whereas a real rebuild would delete
 * the index the alias serves. The refused rebuild is reached by claiming the index in the test and
 * letting the command find it busy, so the assertions below never start one.
 */
public class QueuedAdminJobTest {

  static {
    // Must run before the test support boots the server, which reads the Neo4j env vars. Ports are
    // assigned by the OS, so they cannot collide with the dev server or another test in this module.
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "0",
        "CEDAR_RESOURCE_ADMIN_PORT", "0",
        "CEDAR_RESOURCE_STOP_PORT", "0",
        "CEDAR_REDIS_PERSISTENT_PORT", "1"));
  }

  public static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static String adminAuthHeader;
  private static String userAuthHeader;

  /** The index claims this test took directly, so it can give them back whatever the test does. */
  private final Map<IndexJobGuard.Index, JobClaim> taken = new EnumMap<>(IndexJobGuard.Index.class);

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE);
    CedarConfig cedarConfig = CedarConfig.getInstance(environment);

    TestAuthUtil.installInMemoryUserService(cedarConfig);
    adminAuthHeader = TestAuthUtil.getAdminUserAuthHeader(cedarConfig);
    userAuthHeader = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);

    EmbeddedCedarNeo4j.seed(cedarConfig);

    AbstractResourceServerResource.injectServices(
        new NoOpNodeIndexingService(cedarConfig),
        new IndexUtils(cedarConfig).getNodeSearchingService(),
        new SearchPermissionEnqueueService(cedarConfig),
        new ValuerecommenderReindexQueueService(cedarConfig.getCacheConfig().getPersistent()));
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
  }

  /** Claim an index the way a rebuild does, so the command under test finds it busy. */
  private JobClaim claimIndex(IndexJobGuard.Index index, String command) {
    JobClaim claim = IndexJobGuard.tryStart(index, command).orElseThrow();
    taken.put(index, claim);
    return claim;
  }

  /**
   * The guard and the import manager are process-wide, and the server under test shares them with
   * this class, so a claim left behind would refuse the next test's command. An import the server
   * queued is claimed on its own account and taken back rather than released, since the reset instant
   * is past any deadline a test can have reached.
   */
  @AfterEach
  public void releaseEverything() {
    taken.forEach((index, claim) -> IndexJobGuard.finish(index, claim, null));
    taken.clear();
    ValueSetsImportStatusManager.getInstance()
        .reset(Instant.now().plus(JobClaim.DEADLINE).plus(JobClaim.DEADLINE));
  }

  @Test
  public void aQueuedImportAnswers202NamingTheJobAndWhereToPollIt() throws Exception {
    HttpResponse<String> response = post("/command/load-valuesets-ontology", adminAuthHeader);

    assertEquals(202, response.statusCode(), "the import was queued, not performed");
    JsonNode queued = JsonMapper.MAPPER.readTree(response.body());
    String jobId = queued.get("jobId").asText();
    assertNotNull(jobId);
    assertTrue(location(response).endsWith("/command/load-valuesets-ontology-status/" + jobId),
        location(response));
  }

  /**
   * The identifier the command returned is what makes the answer worth having: it names this import
   * rather than the latest one, so a caller learns what became of the work it asked for.
   */
  @Test
  public void anImportIsPolledByTheIdentifierItReturned() throws Exception {
    String jobId = JsonMapper.MAPPER.readTree(post("/command/load-valuesets-ontology", adminAuthHeader).body())
        .get("jobId").asText();

    HttpResponse<String> polled = get("/command/load-valuesets-ontology-status/" + jobId, userAuthHeader);

    assertEquals(200, polled.statusCode());
    JsonNode job = JsonMapper.MAPPER.readTree(polled.body());
    assertEquals(jobId, job.get("jobId").asText());
    assertNotNull(job.get("importStatus").asText());
  }

  /**
   * A rebuild refused by a running one is told which job holds the index and where to watch it, so
   * the caller has something to wait on rather than only a reason it was refused.
   */
  @Test
  public void aRebuildRefusedByARunningJobNamesThatJobAndWhereToPollIt() throws Exception {
    JobClaim running = claimIndex(IndexJobGuard.Index.SEARCH, "regenerate-search-index");

    HttpResponse<String> response = post("/command/regenerate-search-index", adminAuthHeader, "{\"force\": true}");

    assertEquals(409, response.statusCode());
    JsonNode refusal = JsonMapper.MAPPER.readTree(response.body());
    assertEquals(running.id(), refusal.get("parameters").get("jobId").asText());
    assertTrue(location(response).endsWith("/command/index-job-status/" + running.id()), location(response));
  }

  @Test
  public void aRunningRebuildIsPolledByItsOwnIdentifier() throws Exception {
    JobClaim running = claimIndex(IndexJobGuard.Index.SEARCH, "regenerate-search-index");

    HttpResponse<String> polled = get("/command/index-job-status/" + running.id(), userAuthHeader);

    assertEquals(200, polled.statusCode());
    JsonNode job = JsonMapper.MAPPER.readTree(polled.body());
    assertEquals(running.id(), job.get("jobId").asText());
    assertEquals("RUNNING", job.get("state").asText());
    assertEquals("regenerate-search-index", job.get("command").asText());
  }

  /** The collection status and the job's own route must name the same job, or neither can be trusted. */
  @Test
  public void theStatusOfAnIndexNamesTheJobItReports() throws Exception {
    JobClaim running = claimIndex(IndexJobGuard.Index.SEARCH, "generate-empty-search-index");

    JsonNode statuses = JsonMapper.MAPPER.readTree(get("/command/index-job-status", userAuthHeader).body());

    assertEquals(running.id(), statuses.get("SEARCH").get("jobId").asText());
  }

  /**
   * An identifier nothing answers to is a 404 rather than an empty status. A caller polling its own
   * job has to tell "not finished yet" from "no longer known": the first is worth waiting on, and
   * the second never resolves.
   */
  @Test
  public void anIdentifierNoJobAnswersToIsNotFound() throws Exception {
    assertEquals(404, get("/command/index-job-status/" + UUID.randomUUID(), userAuthHeader).statusCode());
    assertEquals(404,
        get("/command/load-valuesets-ontology-status/" + UUID.randomUUID(), userAuthHeader).statusCode());
  }

  private static String location(HttpResponse<String> response) {
    return response.headers().firstValue("Location")
        .orElseThrow(() -> new AssertionError("the response carried no Location header"));
  }

  private static HttpResponse<String> post(String path, String authHeader) throws Exception {
    return post(path, authHeader, "{}");
  }

  private static HttpResponse<String> post(String path, String authHeader, String body) throws Exception {
    return send(HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .header("Content-Type", "application/json")
        .header("Authorization", authHeader)
        .POST(HttpRequest.BodyPublishers.ofString(body)));
  }

  private static HttpResponse<String> get(String path, String authHeader) throws Exception {
    return send(HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .header("Authorization", authHeader)
        .GET());
  }

  private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
    return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
