package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TextField;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import static org.junit.jupiter.api.Assertions.*;

class InclusionInstanceProtectionTest {
  private ObjectNode study(String fieldName) {
    return new JsonArtifactRenderer().renderTemplateSchemaArtifact(
        TemplateSchemaArtifact.builder().withName("Study")
            .withElementSchema(ElementSchemaArtifact.builder().withName("Principal Investigator")
                .withFieldSchema(TextField.builder().withName(fieldName).build()).build()).build());
  }

  @Test
  void blocksRenamingAFieldInsideAnIncludedElementWhenInstancesExist() {
    assertTrue(CommandInclusionSubgraphResource.requiresNewVersion(1, study("PI Name"), study("Name")));
  }

  @Test
  void allowsStructuralChangesWhenThereAreNoInstances() {
    assertFalse(CommandInclusionSubgraphResource.requiresNewVersion(0, study("PI Name"), study("Name")));
  }

  @Test
  void allowsAnUnchangedDefinitionWhenInstancesExist() {
    ObjectNode template = study("PI Name");
    assertFalse(CommandInclusionSubgraphResource.requiresNewVersion(1, template, template.deepCopy()));
  }
}
