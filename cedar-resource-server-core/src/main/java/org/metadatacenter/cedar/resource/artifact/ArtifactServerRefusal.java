package org.metadatacenter.cedar.resource.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.http.CedarResponseStatus;
import org.metadatacenter.util.json.JsonMapper;

/**
 * The artifact server's refusal of a request a command made of it, carrying the artifact server's own
 * status and reason.
 *
 * <p>A command that reads a document before it writes used to take whatever body came back as the
 * document. A 404 error envelope was then read as an artifact, stamped with a version, and written
 * back, and the caller was told the artifact server refused a document with no {@code @id}. Every
 * read now answers with the document or with this, so the caller hears what the artifact server
 * said, with the status it said it under. A status with no CEDAR equivalent answers as a bad gateway.
 */
public class ArtifactServerRefusal extends CedarProcessingException {

  public ArtifactServerRefusal(int status, String body) {
    super(reasonOf(status, body));
    CedarResponseStatus relayed = CedarResponseStatus.fromStatusCode(status);
    errorPack.status(relayed != null ? relayed : CedarResponseStatus.BAD_GATEWAY);
    CedarErrorKey key = errorKeyOf(body);
    if (key != null) {
      errorPack.errorKey(key);
    }
  }

  private static JsonNode parsed(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      return JsonMapper.STRICT_MAPPER.readTree(body);
    } catch (Exception e) {
      return null;
    }
  }

  private static String reasonOf(int status, String body) {
    JsonNode node = parsed(body);
    String message = node == null ? null : node.path("message").asText(null);
    return message != null && !message.isBlank() ? message : "The artifact server answered " + status;
  }

  private static CedarErrorKey errorKeyOf(String body) {
    JsonNode node = parsed(body);
    String value = node == null ? null : node.path("errorKey").asText(null);
    if (value == null) {
      return null;
    }
    for (CedarErrorKey key : CedarErrorKey.values()) {
      if (key.getValue().equals(value)) {
        return key;
      }
    }
    return null;
  }
}
