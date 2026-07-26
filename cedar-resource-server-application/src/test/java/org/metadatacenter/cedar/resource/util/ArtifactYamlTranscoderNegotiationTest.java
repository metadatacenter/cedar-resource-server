package org.metadatacenter.cedar.resource.util;

import org.junit.jupiter.api.Test;

import jakarta.ws.rs.core.MediaType;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the Accept-header and Content-Type decisions of ArtifactYamlTranscoder. The media type
 * lists mirror what HttpHeaders.getAcceptableMediaTypes() produces: sorted by preference, with a
 * wildcard entry when the Accept header is absent.
 */
public class ArtifactYamlTranscoderNegotiationTest {

  private static final MediaType X_YAML = ArtifactYamlTranscoder.APPLICATION_X_YAML_TYPE;
  private static final MediaType YAML = ArtifactYamlTranscoder.APPLICATION_YAML_TYPE;
  private static final MediaType JSON = MediaType.APPLICATION_JSON_TYPE;

  private Optional<MediaType> negotiate(List<MediaType> acceptable) {
    return ArtifactYamlTranscoder.negotiateResponseType(acceptable);
  }

  @Test
  public void absentAcceptHeaderYieldsJson() {
    assertEquals(Optional.of(JSON), negotiate(null));
    assertEquals(Optional.of(JSON), negotiate(Collections.emptyList()));
  }

  @Test
  public void jsonYieldsJson() {
    assertEquals(Optional.of(JSON), negotiate(asList(JSON)));
  }

  @Test
  public void eitherYamlTypeYieldsTheTypeTheClientAskedFor() {
    assertEquals(Optional.of(X_YAML), negotiate(asList(X_YAML)));
    assertEquals(Optional.of(YAML), negotiate(asList(YAML)));
  }

  @Test
  public void wildcardYieldsJson() {
    assertEquals(Optional.of(JSON), negotiate(asList(MediaType.WILDCARD_TYPE)));
  }

  @Test
  public void yamlPreferredOverJsonWhenListedFirst() {
    assertEquals(Optional.of(YAML), negotiate(asList(YAML, JSON)));
    assertEquals(Optional.of(X_YAML), negotiate(asList(X_YAML, JSON)));
  }

  @Test
  public void jsonPreferredOverYamlWhenListedFirst() {
    assertEquals(Optional.of(JSON), negotiate(asList(JSON, YAML)));
  }

  @Test
  public void unsupportedTypeAloneIsNotAcceptable() {
    assertEquals(Optional.empty(), negotiate(asList(MediaType.TEXT_HTML_TYPE)));
  }

  @Test
  public void unsupportedTypeFollowedByWildcardYieldsJson() {
    // The browser pattern: Accept: text/html, */*;q=0.8
    assertEquals(Optional.of(JSON), negotiate(asList(MediaType.TEXT_HTML_TYPE, MediaType.WILDCARD_TYPE)));
  }

  @Test
  public void unsupportedTypeFollowedByYamlYieldsYaml() {
    assertEquals(Optional.of(YAML), negotiate(asList(MediaType.TEXT_HTML_TYPE, YAML)));
  }

  @Test
  public void isYamlMatchesBothYamlMediaTypes() {
    assertTrue(ArtifactYamlTranscoder.isYaml(new MediaType("application", "x-yaml")));
    assertTrue(ArtifactYamlTranscoder.isYaml(new MediaType("application", "yaml")));
    assertTrue(ArtifactYamlTranscoder.isYaml(MediaType.valueOf("application/yaml; charset=utf-8")));
  }

  @Test
  public void isYamlRejectsOtherAndWildcardTypes() {
    assertFalse(ArtifactYamlTranscoder.isYaml(null));
    assertFalse(ArtifactYamlTranscoder.isYaml(JSON));
    assertFalse(ArtifactYamlTranscoder.isYaml(MediaType.WILDCARD_TYPE));
    assertFalse(ArtifactYamlTranscoder.isYaml(new MediaType("text", "yaml")));
  }

  @Test
  public void isJsonMatchesOnlyJson() {
    assertTrue(ArtifactYamlTranscoder.isJson(JSON));
    assertFalse(ArtifactYamlTranscoder.isJson(YAML));
    assertFalse(ArtifactYamlTranscoder.isJson(null));
  }

}
