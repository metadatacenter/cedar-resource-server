package org.metadatacenter.cedar.resource.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.metadatacenter.artifacts.model.core.fields.constraints.VersionSpec;
import org.metadatacenter.artifacts.util.ControlledTermVersionFreezer;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * A {@link ControlledTermVersionFreezer.VersionResolver} that calls the terminology server's
 * resolve-current endpoints so {@link org.metadatacenter.artifacts.util.TemplateVersionFreezer} can
 * pin a template's controlled-term constraints at publish time.
 *
 * <b>Fail-safe by design.</b> Any non-200 response, timeout, or parse/network error yields empty, so
 * freeze-on-publish can never block or break a publish. When the terminology local store is off (the
 * production default), resolve-current returns 404 and nothing is pinned — the published artifact is
 * unchanged.
 */
public class TerminologyVersionResolver implements ControlledTermVersionFreezer.VersionResolver {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String base;          // terminology base URL, trailing slash guaranteed
  private final String authorization; // the publishing user's Authorization header (or null)
  private final RequestSender sender;

  @FunctionalInterface
  interface RequestSender {
    HttpResponse<String> send(HttpRequest request) throws Exception;
  }

  public TerminologyVersionResolver(String terminologyBaseUrl, String authorizationHeader) {
    this(terminologyBaseUrl, authorizationHeader, defaultSender());
  }

  TerminologyVersionResolver(String terminologyBaseUrl, String authorizationHeader, RequestSender sender) {
    this.base = terminologyBaseUrl.endsWith("/") ? terminologyBaseUrl : terminologyBaseUrl + "/";
    this.authorization = authorizationHeader;
    this.sender = sender;
  }

  @Override
  public Optional<VersionSpec> currentVersionByAcronym(String acronym) {
    return get("bioportal/ontologies/" + enc(acronym) + "/versions/current");
  }

  @Override
  public Optional<VersionSpec> currentVersionByClassUri(URI classUri) {
    return get("bioportal/classes/version-current?uri=" + enc(classUri.toString()));
  }

  @Override
  public Optional<VersionSpec> currentVersionByValueSetCollection(String vsCollection) {
    return get("bioportal/vs-collections/version-current?collection=" + enc(vsCollection));
  }

  private Optional<VersionSpec> get(String path) {
    try {
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
          .timeout(Duration.ofSeconds(3)).GET();
      if (authorization != null) {
        request.header("Authorization", authorization);
      }
      HttpResponse<String> response = sender.send(request.build());
      if (response.statusCode() != 200) {
        return Optional.empty();
      }
      JsonNode triple = MAPPER.readTree(response.body());
      JsonNode id = triple.get("id");
      if (id == null || !id.isTextual()) {
        return Optional.empty();
      }
      return Optional.of(new VersionSpec(id.asText(),
          text(triple, "effectiveDate"), text(triple, "declaredVersion")));
    } catch (Exception failSafe) {
      return Optional.empty(); // never block or break publish
    }
  }

  private static Optional<String> text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isTextual() ? Optional.of(value.asText()) : Optional.empty();
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static RequestSender defaultSender() {
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    return request -> http.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
