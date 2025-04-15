package org.metadatacenter.cedar.deltafinder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Before;
import org.junit.Test;
import org.metadatacenter.artifacts.model.core.Artifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;

import java.io.InputStream;

public class TestSimpleTemplate {

  private ObjectMapper objectMapper;

  @Before
  public void setEnvironment() {
    objectMapper = new ObjectMapper();
  }

  @Test
  public void testSimpleTemplate1() throws Exception {
    InputStream oldStream = getClass().getClassLoader().getResourceAsStream("deltafinder/template-01/template-01-old" +
        ".json");
    InputStream newStream = getClass().getClassLoader().getResourceAsStream("deltafinder/template-01/template-01-new" +
        ".json");

    if (oldStream == null || newStream == null) {
      throw new RuntimeException("Could not load test templates from resources.");
    }

    JsonNode oldNode = objectMapper.readTree(oldStream);
    JsonNode newNode = objectMapper.readTree(newStream);

    JsonArtifactReader reader = new JsonArtifactReader();
    TemplateSchemaArtifact oldModelArtifact = reader.readTemplateSchemaArtifact((ObjectNode) oldNode);
    TemplateSchemaArtifact newModelArtifact = reader.readTemplateSchemaArtifact((ObjectNode) newNode);


    System.out.println(oldNode.toString());
    System.out.println(newNode.toString());

    System.out.println(oldModelArtifact);
    System.out.println(newModelArtifact);

    DeltaFinder finder = new DeltaFinder();
    Delta delta = finder.findDelta(oldModelArtifact, newModelArtifact);
    System.out.println(delta);
  }
}