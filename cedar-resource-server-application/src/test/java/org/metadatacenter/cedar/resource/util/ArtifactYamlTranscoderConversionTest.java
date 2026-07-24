package org.metadatacenter.cedar.resource.util;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Test;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.util.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Round-trip tests for the YAML/JSON conversion of ArtifactYamlTranscoder. The fixtures are copies
 * of cedar-artifact-library test artifacts, so they reflect the artifact forms the library
 * guarantees to read and render.
 */
public class ArtifactYamlTranscoderConversionTest {

  private String readFixture(String name) throws IOException {
    try (InputStream is = getClass().getResourceAsStream("/artifacts/" + name)) {
      assertNotNull("Missing test fixture: " + name, is);
      return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  public void templateYamlConvertsToJson() throws IOException {
    String yaml = readFixture("SimpleTemplate.yaml");

    String json = ArtifactYamlTranscoder.yamlToJsonString(yaml, CedarResourceType.TEMPLATE);

    JsonNode node = JsonMapper.MAPPER.readTree(json);
    assertEquals("https://repo.metadatacenter.org/templates/7b8977ed-c4d7-4c29-b202-53e38a41c723",
        node.get("@id").asText());
    assertEquals("Simple Template", node.get("schema:name").asText());
    assertTrue("The JSON Schema form should carry a properties object", node.has("properties"));
    assertTrue("The JSON-LD form should carry a context", node.has("@context"));
  }

  @Test
  public void templateJsonConvertsToYaml() throws IOException {
    JsonNode template = JsonMapper.MAPPER.readTree(readFixture("SimpleTemplate.json"));

    String yaml = ArtifactYamlTranscoder.jsonToYaml(template, CedarResourceType.TEMPLATE, false);

    assertNotNull(yaml);
    assertTrue(yaml.contains("Simple Template"));
  }

  @Test
  public void templateJsonSurvivesYamlRoundTrip() throws IOException {
    JsonNode original = JsonMapper.MAPPER.readTree(readFixture("SimpleTemplate.json"));

    String yaml = ArtifactYamlTranscoder.jsonToYaml(original, CedarResourceType.TEMPLATE, false);
    JsonNode roundTripped =
        JsonMapper.MAPPER.readTree(ArtifactYamlTranscoder.yamlToJsonString(yaml, CedarResourceType.TEMPLATE));

    assertEquals(original.get("@id"), roundTripped.get("@id"));
    assertEquals(original.get("schema:name"), roundTripped.get("schema:name"));
    assertEquals(original.get("pav:version"), roundTripped.get("pav:version"));
    assertEquals(original.get("bibo:status"), roundTripped.get("bibo:status"));
  }

  @Test
  public void elementJsonSurvivesYamlRoundTrip() throws IOException {
    JsonNode original = JsonMapper.MAPPER.readTree(readFixture("element-001.json"));

    String yaml = ArtifactYamlTranscoder.jsonToYaml(original, CedarResourceType.ELEMENT, false);
    JsonNode roundTripped =
        JsonMapper.MAPPER.readTree(ArtifactYamlTranscoder.yamlToJsonString(yaml, CedarResourceType.ELEMENT));

    assertEquals(original.get("@id"), roundTripped.get("@id"));
    assertEquals(original.get("schema:name"), roundTripped.get("schema:name"));
  }

  @Test
  public void fieldJsonSurvivesYamlRoundTrip() throws IOException {
    JsonNode original = JsonMapper.MAPPER.readTree(readFixture("StandaloneField.json"));

    String yaml = ArtifactYamlTranscoder.jsonToYaml(original, CedarResourceType.FIELD, false);
    JsonNode roundTripped =
        JsonMapper.MAPPER.readTree(ArtifactYamlTranscoder.yamlToJsonString(yaml, CedarResourceType.FIELD));

    assertEquals(original.get("@id"), roundTripped.get("@id"));
    assertEquals(original.get("schema:name"), roundTripped.get("schema:name"));
  }

  @Test
  public void instanceJsonSurvivesYamlRoundTrip() throws IOException {
    JsonNode original = JsonMapper.MAPPER.readTree(readFixture("SimpleInstance.json"));

    String yaml = ArtifactYamlTranscoder.jsonToYaml(original, CedarResourceType.INSTANCE, false);
    JsonNode roundTripped =
        JsonMapper.MAPPER.readTree(ArtifactYamlTranscoder.yamlToJsonString(yaml, CedarResourceType.INSTANCE));

    assertEquals(original.get("@id"), roundTripped.get("@id"));
    assertEquals(original.get("schema:isBasedOn"), roundTripped.get("schema:isBasedOn"));
  }

  @Test
  public void compactYamlIsShorterThanFullYaml() throws IOException {
    JsonNode template = JsonMapper.MAPPER.readTree(readFixture("SimpleTemplate.json"));

    String full = ArtifactYamlTranscoder.jsonToYaml(template, CedarResourceType.TEMPLATE, false);
    String compact = ArtifactYamlTranscoder.jsonToYaml(template, CedarResourceType.TEMPLATE, true);

    assertTrue(compact.length() < full.length());
  }

  @Test(expected = Exception.class)
  public void malformedYamlIsRejected() throws IOException {
    ArtifactYamlTranscoder.yamlToJsonString("this is: [not: valid template yaml", CedarResourceType.TEMPLATE);
  }

  @Test
  public void minimalYamlIsAccepted() throws IOException {
    // The minimal authoring form: no id, the system supplies the rest
    String minimal = "type: template\n"
        + "name: Minimal Study\n"
        + "children:\n"
        + "- key: study-name\n"
        + "  type: text-field\n"
        + "  name: Study Name\n";

    String json = ArtifactYamlTranscoder.yamlToJsonString(minimal, CedarResourceType.TEMPLATE);

    JsonNode node = JsonMapper.MAPPER.readTree(json);
    assertEquals("Minimal Study", node.get("schema:name").asText());
  }

  @Test
  public void compactYamlIsRejected() throws IOException {
    // The compact form keeps the id but strips the system-recorded keys; storing it would
    // silently regenerate that content
    JsonNode template = JsonMapper.MAPPER.readTree(readFixture("SimpleTemplate.json"));
    String compact = ArtifactYamlTranscoder.jsonToYaml(template, CedarResourceType.TEMPLATE, true);

    try {
      ArtifactYamlTranscoder.yamlToJsonString(compact, CedarResourceType.TEMPLATE);
      fail("The compact form must be rejected");
    } catch (ArtifactYamlTranscoder.CompactYamlBodyException e) {
      assertTrue(e.getMessage().contains("compact form"));
      assertTrue(e.getMessage().contains("minimal-and-full"));
    }
  }

  @Test(expected = Exception.class)
  public void yamlOfTheWrongArtifactTypeIsRejected() throws IOException {
    String yaml = readFixture("SimpleTemplate.yaml");
    ArtifactYamlTranscoder.yamlToJsonString(yaml, CedarResourceType.FIELD);
  }

}
