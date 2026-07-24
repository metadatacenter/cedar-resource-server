package org.metadatacenter.cedar.resource.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.metadatacenter.artifacts.model.core.Artifact;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.artifacts.model.reader.YamlArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.artifacts.model.tools.YamlSerializer;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.util.json.JsonMapper;

import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * Converts artifacts between their stored JSON Schema/JSON-LD form and the YAML serialization
 * provided by the cedar-artifact-library. The storage layer remains JSON-only; YAML exists purely
 * as a request/response representation produced on the fly.
 *
 * Two YAML media types are recognized: application/x-yaml, the value of
 * HttpConstants.CONTENT_TYPE_APPLICATION_YAML used across CEDAR, and application/yaml, the type
 * registered by RFC 9512.
 */
public final class ArtifactYamlTranscoder {

  public static final MediaType APPLICATION_X_YAML_TYPE = new MediaType("application", "x-yaml");
  public static final MediaType APPLICATION_YAML_TYPE = new MediaType("application", "yaml");

  private static final List<MediaType> YAML_TYPES = List.of(APPLICATION_X_YAML_TYPE, APPLICATION_YAML_TYPE);

  private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

  private ArtifactYamlTranscoder() {
  }

  /**
   * Selects the response media type from the acceptable media types of a request. The list is
   * expected in preference order, as returned by HttpHeaders.getAcceptableMediaTypes(). JSON is
   * the default: it matches wildcards, and an absent Accept header yields a wildcard entry. When
   * the client asks for YAML, the returned type is the concrete YAML type it asked for, so the
   * response can echo it. An empty Optional means the client asked only for types the server can
   * not produce.
   */
  public static Optional<MediaType> negotiateResponseType(List<MediaType> acceptableMediaTypes) {
    if (acceptableMediaTypes == null || acceptableMediaTypes.isEmpty()) {
      return Optional.of(MediaType.APPLICATION_JSON_TYPE);
    }
    for (MediaType requested : acceptableMediaTypes) {
      if (requested.isCompatible(MediaType.APPLICATION_JSON_TYPE)) {
        return Optional.of(MediaType.APPLICATION_JSON_TYPE);
      }
      for (MediaType yamlType : YAML_TYPES) {
        if (requested.isCompatible(yamlType)) {
          return Optional.of(yamlType);
        }
      }
    }
    return Optional.empty();
  }

  public static boolean isJson(MediaType mediaType) {
    return MediaType.APPLICATION_JSON_TYPE.equals(mediaType);
  }

  /**
   * Decides whether a request body is YAML based on its Content-Type. Parameters such as charset
   * are ignored; wildcards do not match.
   */
  public static boolean isYaml(MediaType contentType) {
    if (contentType == null) {
      return false;
    }
    for (MediaType yamlType : YAML_TYPES) {
      if (yamlType.getType().equalsIgnoreCase(contentType.getType())
          && yamlType.getSubtype().equalsIgnoreCase(contentType.getSubtype())) {
        return true;
      }
    }
    return false;
  }

  /**
   * The keys the system records about a stored artifact. The compact YAML form strips all of
   * them while keeping the id; the minimal authoring form carries none of them and no id
   * either. An id with none of these present is therefore the signature of compact input.
   */
  private static final List<String> SYSTEM_RECORDED_KEYS =
      List.of("modelVersion", "version", "status", "createdOn", "createdBy", "modifiedOn", "modifiedBy");

  /**
   * Thrown when a YAML request body is the compact form, which is a lossy read-time convenience
   * and can not be stored.
   */
  public static final class CompactYamlBodyException extends RuntimeException {
    private CompactYamlBodyException(String message) {
      super(message);
    }
  }

  /**
   * Converts a YAML artifact serialization into the JSON Schema/JSON-LD form expected by the
   * artifact server. The YAML is parsed, read into the artifact model, and rendered as JSON.
   *
   * The compact form is rejected: it carries the artifact's id but strips the system-recorded
   * keys, so storing it would silently regenerate that content. The minimal authoring form -
   * no id, the system supplies the rest - and the full form both pass through.
   */
  public static String yamlToJsonString(String yamlContent, CedarResourceType resourceType) throws IOException {
    LinkedHashMap<String, Object> yamlMap =
        YAML_MAPPER.readValue(yamlContent, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    if (yamlMap.get("id") != null && SYSTEM_RECORDED_KEYS.stream().noneMatch(yamlMap::containsKey)) {
      throw new CompactYamlBodyException("The YAML body appears to be the compact form: it carries an id but none "
          + "of the system-recorded keys (version, status, modelVersion, provenance). The compact form is a lossy "
          + "read-time convenience and can not be stored. Submit the full form, or omit the id to author minimally. "
          + "See https://metadatacenter.readthedocs.io/en/latest/yaml-spec/minimal-and-full/");
    }
    // The lenient reader tolerates an absent modelVersion and defaults it, which the minimal
    // authoring form relies on; compact input never reaches the reader, it is rejected above
    YamlArtifactReader reader = new YamlArtifactReader(true);
    JsonArtifactRenderer renderer = new JsonArtifactRenderer();
    ObjectNode rendered = switch (resourceType) {
      case TEMPLATE -> renderer.renderTemplateSchemaArtifact(reader.readTemplateSchemaArtifact(yamlMap));
      case ELEMENT -> renderer.renderElementSchemaArtifact(reader.readElementSchemaArtifact(yamlMap));
      case FIELD -> renderer.renderFieldSchemaArtifact(reader.readFieldSchemaArtifact(yamlMap));
      case INSTANCE -> renderer.renderTemplateInstanceArtifact(reader.readTemplateInstanceArtifact(yamlMap));
      default -> throw new IllegalArgumentException("YAML is not supported for resource type: " + resourceType);
    };
    return JsonMapper.MAPPER.writeValueAsString(rendered);
  }

  /**
   * Converts an artifact's JSON Schema/JSON-LD form into its YAML serialization.
   */
  public static String jsonToYaml(JsonNode artifactNode, CedarResourceType resourceType, boolean compact) {
    Artifact artifact = readJsonArtifact((ObjectNode) artifactNode, resourceType);
    return YamlSerializer.getYAML(artifact, compact, true);
  }

  private static Artifact readJsonArtifact(ObjectNode artifactNode, CedarResourceType resourceType) {
    JsonArtifactReader reader = new JsonArtifactReader();
    return switch (resourceType) {
      case TEMPLATE -> reader.readTemplateSchemaArtifact(artifactNode);
      case ELEMENT -> reader.readElementSchemaArtifact(artifactNode);
      case FIELD -> reader.readFieldSchemaArtifact(artifactNode);
      case INSTANCE -> reader.readTemplateInstanceArtifact(artifactNode);
      default -> throw new IllegalArgumentException("YAML is not supported for resource type: " + resourceType);
    };
  }

}
