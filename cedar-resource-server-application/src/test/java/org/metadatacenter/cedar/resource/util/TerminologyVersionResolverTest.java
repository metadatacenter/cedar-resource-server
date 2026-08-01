package org.metadatacenter.cedar.resource.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.metadatacenter.artifacts.model.core.fields.constraints.VersionSpec;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hermetic tests for request construction, response parsing, and fail-safe resolution. */
class TerminologyVersionResolverTest {

  @Test
  void acronymLookupEncodesThePathPropagatesAuthorizationAndParsesTheFullTriple() {
    AtomicReference<HttpRequest> seen = new AtomicReference<>();
    TerminologyVersionResolver resolver = resolver("https://terms.example/api/", "Bearer abc", seen,
        200, "{\"id\":\"hash-1\",\"effectiveDate\":\"2026-07-01\",\"declaredVersion\":\"v3\"}");

    Optional<VersionSpec> result = resolver.currentVersionByAcronym("MY ONT/1");

    assertEquals("https://terms.example/api/bioportal/ontologies/MY+ONT%2F1/versions/current",
        seen.get().uri().toString());
    assertEquals(Optional.of("Bearer abc"), seen.get().headers().firstValue("Authorization"));
    assertEquals(Optional.of(new VersionSpec("hash-1", Optional.of("2026-07-01"), Optional.of("v3"))), result);
  }

  @Test
  void classLookupEncodesTheClassUriAsAQueryParameter() {
    AtomicReference<HttpRequest> seen = new AtomicReference<>();
    TerminologyVersionResolver resolver = resolver("https://terms.example", null, seen, 200, "{\"id\":\"h\"}");

    resolver.currentVersionByClassUri(URI.create("http://example.org/C-1?x=y&z=a"));

    assertEquals("https://terms.example/bioportal/classes/version-current?uri=http%3A%2F%2Fexample.org%2FC-1%3Fx%3Dy%26z%3Da",
        seen.get().uri().toString());
    assertTrue(seen.get().headers().firstValue("Authorization").isEmpty());
  }

  @Test
  void valueSetLookupNormalizesABaseWithoutATrailingSlashAndEncodesTheCollection() {
    AtomicReference<HttpRequest> seen = new AtomicReference<>();
    TerminologyVersionResolver resolver = resolver("https://terms.example/root", null, seen, 200, "{\"id\":\"h\"}");

    resolver.currentVersionByValueSetCollection("collection/a b");

    assertEquals("https://terms.example/root/bioportal/vs-collections/version-current?collection=collection%2Fa+b",
        seen.get().uri().toString());
  }

  // Two cases.
  @ParameterizedTest
  @ValueSource(ints = {404, 500})
  void anyNonSuccessfulStatusIsAnUnresolvedVersion(int status) {
    TerminologyVersionResolver resolver = resolver("https://terms.example", null,
        new AtomicReference<>(), status, "{\"id\":\"ignored\"}");

    assertTrue(resolver.currentVersionByAcronym("DOID").isEmpty());
  }

  @Test
  void malformedJsonIsAnUnresolvedVersion() {
    TerminologyVersionResolver resolver = resolver("https://terms.example", null,
        new AtomicReference<>(), 200, "not-json");

    assertTrue(resolver.currentVersionByAcronym("DOID").isEmpty());
  }

  @Test
  void aResponseWithoutAnIdIsAnUnresolvedVersion() {
    TerminologyVersionResolver resolver = resolver("https://terms.example", null,
        new AtomicReference<>(), 200, "{\"effectiveDate\":\"2026-07-01\"}");

    assertTrue(resolver.currentVersionByAcronym("DOID").isEmpty());
  }

  @Test
  void aNonTextualIdIsAnUnresolvedVersion() {
    TerminologyVersionResolver resolver = resolver("https://terms.example", null,
        new AtomicReference<>(), 200, "{\"id\":42}");

    assertTrue(resolver.currentVersionByAcronym("DOID").isEmpty());
  }

  @Test
  void nonTextualOptionalFieldsAreIgnoredWithoutDiscardingAValidId() {
    TerminologyVersionResolver resolver = resolver("https://terms.example", null,
        new AtomicReference<>(), 200, "{\"id\":\"h\",\"effectiveDate\":42,\"declaredVersion\":false}");

    assertEquals(Optional.of(new VersionSpec("h", Optional.empty(), Optional.empty())),
        resolver.currentVersionByAcronym("DOID"));
  }

  @Test
  void senderFailuresAreFailSafeAndReturnUnresolved() {
    TerminologyVersionResolver resolver = new TerminologyVersionResolver(
        "https://terms.example", null, request -> { throw new IllegalStateException("network down"); });

    assertTrue(resolver.currentVersionByAcronym("DOID").isEmpty());
  }

  private TerminologyVersionResolver resolver(String base, String authorization,
                                               AtomicReference<HttpRequest> seen,
                                               int status, String body) {
    return new TerminologyVersionResolver(base, authorization, request -> {
      seen.set(request);
      return new StubResponse(request, status, body);
    });
  }

  private record StubResponse(HttpRequest request, int statusCode, String body) implements HttpResponse<String> {
    @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
    @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
    @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
    @Override public URI uri() { return request.uri(); }
    @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
  }
}
