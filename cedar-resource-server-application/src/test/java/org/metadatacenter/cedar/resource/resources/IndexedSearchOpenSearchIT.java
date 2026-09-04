package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.apache.http.HttpHost;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.server.security.model.auth.CedarNodeMaterializedPermissions;
import org.metadatacenter.server.security.model.permission.resource.FilesystemResourcePermission;
import org.metadatacenter.server.search.elasticsearch.service.NoOpNodeIndexingService;
import org.metadatacenter.server.search.permission.SearchPermissionEnqueueService;
import org.metadatacenter.server.search.util.IndexUtils;
import org.metadatacenter.server.valuerecommender.ValuerecommenderReindexQueueService;
import org.metadatacenter.util.json.JsonMapper;
import org.metadatacenter.util.test.EmbeddedCedarNeo4j;
import org.metadatacenter.util.test.TestAuthUtil;
import org.opensearch.action.admin.indices.delete.DeleteIndexRequest;
import org.opensearch.action.admin.indices.refresh.RefreshRequest;
import org.opensearch.action.delete.DeleteRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.client.indices.CreateIndexRequest;
import org.opensearch.client.indices.GetIndexRequest;
import org.opensearch.common.xcontent.XContentType;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The indexed half of the resource-server search contract, executed against OpenSearch. Ordinary
 * resource tests deliberately inject a no-op index; this profile-only test proves that the HTTP
 * routes retain the permission filter and that a deep continuation survives real index semantics.
 */
public class IndexedSearchOpenSearchIT {

  private static final String OPENSEARCH_HOST =
      System.getenv().getOrDefault("CEDAR_OPENSEARCH_HOST", "127.0.0.1");
  private static final String OPENSEARCH_PORT =
      System.getenv().getOrDefault("CEDAR_OPENSEARCH_REST_PORT", "9200");
  private static final String INDEX_NAME = "cedar-search";
  private static final String RUN = UUID.randomUUID().toString().toLowerCase();
  private static final String TERM = "wirepermissionprobe" + RUN.replace("-", "");
  private static final String WALK_TERM = "wirewalkprobe" + RUN.replace("-", "");
  private static final String PRIVATE_ID = templateId("private");
  private static final String SHARED_ID = templateId("shared");
  private static final Set<String> SEEDED_DOCUMENT_IDS = new HashSet<>();

  static {
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "0",
        "CEDAR_RESOURCE_ADMIN_PORT", "0",
        "CEDAR_RESOURCE_STOP_PORT", "0",
        "CEDAR_REDIS_PERSISTENT_PORT", "1",
        "CEDAR_OPENSEARCH_HOST", OPENSEARCH_HOST,
        "CEDAR_OPENSEARCH_REST_PORT", OPENSEARCH_PORT,
        "CEDAR_OPENSEARCH_TRANSPORT_PORT", "9300"));
  }

  public static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class,
          ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static RestHighLevelClient openSearch;
  private static CedarConfig cedarConfig;
  private static String user1Auth;
  private static String user2Auth;
  private static boolean createdIndex;

  @BeforeAll
  static void oneTimeSetUp() throws Exception {
    SERVER.before();
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE);
    cedarConfig = CedarConfig.getInstance(environment);
    TestAuthUtil.installInMemoryUserService(cedarConfig);
    EmbeddedCedarNeo4j.seed(cedarConfig);

    AbstractResourceServerResource.injectServices(
        new NoOpNodeIndexingService(cedarConfig),
        new IndexUtils(cedarConfig).getNodeSearchingService(),
        new SearchPermissionEnqueueService(cedarConfig),
        new ValuerecommenderReindexQueueService(cedarConfig.getCacheConfig().getPersistent()));

    user1Auth = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
    user2Auth = TestAuthUtil.getTestUser2AuthHeader(cedarConfig);
    String user1Id = TestAuthUtil.getTestUser1(cedarConfig).getId();
    String user2Id = TestAuthUtil.getTestUser2(cedarConfig).getId();

    openSearch = new RestHighLevelClient(RestClient.builder(
        new HttpHost(OPENSEARCH_HOST, Integer.parseInt(OPENSEARCH_PORT), "http")));
    createdIndex = !openSearch.indices().exists(new GetIndexRequest(INDEX_NAME), RequestOptions.DEFAULT);
    if (createdIndex) {
      openSearch.indices().create(new CreateIndexRequest(INDEX_NAME)
          .mapping(testMapping(), XContentType.JSON), RequestOptions.DEFAULT);
    }

    index(PRIVATE_ID, TERM + " private", List.of(readKey(user1Id)));
    index(SHARED_ID, TERM + " shared", List.of(readKey(user1Id), readKey(user2Id)));
    for (int i = 0; i < 5; i++) {
      index(templateId("walk-" + i), WALK_TERM + " " + i, List.of(readKey(user2Id)));
    }
    refresh();
  }

  @AfterAll
  static void oneTimeTearDown() throws Exception {
    try {
      if (openSearch != null) {
        try {
          if (createdIndex) {
            openSearch.indices().delete(new DeleteIndexRequest(INDEX_NAME), RequestOptions.DEFAULT);
          } else {
            for (String documentId : SEEDED_DOCUMENT_IDS) {
              openSearch.delete(new DeleteRequest(INDEX_NAME, documentId), RequestOptions.DEFAULT);
            }
            refresh();
          }
        } finally {
          openSearch.close();
        }
      }
    } finally {
      SERVER.after();
    }
  }

  @Test
  void termSearchReturnsOnlyDocumentsTheCallerMayRead() throws Exception {
    Set<String> ownerResults = resourceIds(get("/search?q=" + enc(TERM) + "&limit=20", user1Auth));
    assertEquals(Set.of(templateIri(PRIVATE_ID), templateIri(SHARED_ID)), ownerResults);

    Set<String> readerResults = resourceIds(get("/search?q=" + enc(TERM) + "&limit=20", user2Auth));
    assertEquals(Set.of(templateIri(SHARED_ID)), readerResults);
    assertFalse(readerResults.contains(templateIri(PRIVATE_ID)));
  }

  @Test
  void searchDeepContinuationReturnsEveryPermittedDocumentExactlyOnce() throws Exception {
    List<String> walked = new ArrayList<>();
    String continuation = "start";
    int requests = 0;

    while (continuation != null && requests++ < 10) {
      JsonNode page = get("/search-deep?q=" + enc(WALK_TERM)
          + "&limit=2&continuation=" + enc(continuation), user2Auth);
      for (JsonNode resource : page.path("resources")) {
        walked.add(resource.path("@id").asText());
      }
      continuation = page.path("continuation").isTextual()
          ? page.path("continuation").asText()
          : null;
    }

    assertEquals(5, walked.size());
    assertEquals(5, new HashSet<>(walked).size());
    assertEquals(SEEDED_DOCUMENT_IDS.stream()
            .filter(id -> id.contains("walk-"))
            .map(IndexedSearchOpenSearchIT::templateIri)
            .collect(java.util.stream.Collectors.toSet()),
        new HashSet<>(walked));
  }

  private static JsonNode get(String path, String authHeader) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .header("Authorization", authHeader)
        .GET()
        .build();
    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    return JsonMapper.MAPPER.readTree(response.body());
  }

  private static Set<String> resourceIds(JsonNode response) {
    Set<String> ids = new HashSet<>();
    for (JsonNode resource : response.path("resources")) {
      ids.add(resource.path("@id").asText());
    }
    return ids;
  }

  private static void index(String documentId, String name, List<String> users) throws Exception {
    String cedarId = templateIri(documentId);
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("@id", cedarId);
    info.put("resourceType", "template");
    info.put("schema:name", name);

    Map<String, Object> document = new LinkedHashMap<>();
    document.put("cid", cedarId);
    document.put("summaryText", name);
    document.put("users", users);
    document.put("info", info);

    openSearch.index(new IndexRequest(INDEX_NAME).id(documentId).source(document), RequestOptions.DEFAULT);
    SEEDED_DOCUMENT_IDS.add(documentId);
  }

  private static void refresh() throws Exception {
    openSearch.indices().refresh(new RefreshRequest(INDEX_NAME), RequestOptions.DEFAULT);
  }

  private static String templateId(String suffix) {
    return "search-wire-it-" + RUN + "-" + suffix;
  }

  private static String templateIri(String documentId) {
    return "https://repo.metadatacenter.org/templates/" + documentId;
  }

  private static String readKey(String userId) {
    return CedarNodeMaterializedPermissions.getKey(userId, FilesystemResourcePermission.READ);
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String testMapping() {
    return """
        {"properties":{
          "cid":{"type":"keyword"},
          "summaryText":{"type":"text","fields":{"raw":{"type":"text","analyzer":"standard"}}},
          "users":{"type":"keyword"},
          "computedEverybodyPermission":{"type":"keyword"},
          "info":{"properties":{
            "@id":{"type":"keyword"},
            "resourceType":{"type":"keyword"},
            "schema:name":{"type":"keyword","fields":{"raw":{"type":"text","analyzer":"standard"}}}
          }}
        }}
        """;
  }
}
