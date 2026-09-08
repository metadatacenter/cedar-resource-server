package org.metadatacenter.cedar.resource.resources;

import org.junit.jupiter.api.Test;
import org.metadatacenter.util.test.OpenApiSuccessContract;

import java.io.IOException;
import java.io.InputStream;

/**
 * Holds this service's committed OpenAPI document to the estate's rule that a success payload is
 * described rather than merely acknowledged.
 *
 * <p>Only three of the eleven spec-shipping services verified their committed document at all, and
 * none of them checked the success side. A 2xx with no schema, a dangling reference, an empty schema
 * and a JSON body typed as a bare string all leave a client generator with nothing to work from, and
 * each is cheap to reintroduce by adding a route and forgetting an annotation.</p>
 *
 * <p>This is the largest surface in the estate and the one an integrator meets first, and two thirds
 * of its operations described nothing. The exemptions below are the writes whose handler reads no
 * body and the success responses that carry none; each must stay true of the document, so one that
 * stops being true fails here rather than lingering.</p>
 */
class OpenApiSuccessContractTest {

  @Test
  void everySuccessPayloadIsDescribed() throws IOException {
    try (InputStream input = getClass().getResourceAsStream("/assets/swagger-api/swagger.json")) {
      OpenApiSuccessContract.assertDescribed(input,
          // Administrative commands whose only input is the index or import they claim.
          "POST /command/generate-empty-rules-index",
          "POST /command/generate-empty-search-index",
          "POST /command/load-valuesets-ontology",
          "POST /command/reset-rules-index-job",
          "POST /command/reset-search-index-job",
          "POST /command/reset-valuesets-import",
          // Reads the artifact named in the path. The POST exists for callers that predate the GET.
          "POST /template-elements/{template_element_id}/download",
          "POST /template-fields/{template_field_id}/download",
          "POST /template-instances/{template_instance_id}/download",
          "POST /templates/{template_id}/download",
          // Content is gone and downstream cleanup is still running, which needs no body.
          "DELETE /templates/{template_id} 202",
          "DELETE /template-elements/{template_element_id} 202",
          "DELETE /template-fields/{template_field_id} 202",
          "DELETE /template-instances/{template_instance_id} 202",
          // Provisions the user's objects for the Keycloak listener, which reads no body back.
          "POST /command/auth-user-callback 201",
          // Reached only when the artifact server returns no entity, which leaves nothing to
          // return. The 201 beside each of these carries the artifact.
          "POST /command/copy-artifact-to-folder 200",
          "POST /command/create-draft-artifact 200");
    }
  }
}
