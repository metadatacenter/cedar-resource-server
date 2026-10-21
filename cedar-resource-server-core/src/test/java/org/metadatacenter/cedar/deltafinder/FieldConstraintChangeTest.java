package org.metadatacenter.cedar.deltafinder;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.core.ListField;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TextField;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.cedar.deltafinder.change.Change;
import org.metadatacenter.cedar.deltafinder.change.ConstraintChange;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A template with instances is updated in place only when its delta is empty. A change to the constraints
 * of a field that keeps its name must therefore be reported, or the stored instances fall out of step
 * with their template.
 */
public class FieldConstraintChangeTest {

  static Stream<Arguments> tighteningChanges() {
    return Stream.of(
        Arguments.of("made required",
            TextField.builder().withName("Notes").build(),
            TextField.builder().withName("Notes").withRequiredValue(true).build()),
        Arguments.of("given a pattern",
            TextField.builder().withName("Notes").build(),
            TextField.builder().withName("Notes").withRegex("[A-Z]+").build()));
  }

  /** A stored instance may hold no value, or one the new rule refuses, so each change is destructive. */
  @ParameterizedTest(name = "a field {0}")
  @MethodSource("tighteningChanges")
  public void aConstraintStoredValuesMayFailIsADestructiveChange(String change, FieldSchemaArtifact before,
                                                                FieldSchemaArtifact after) {
    Delta delta = new DeltaFinder().findDelta(templateWith(before), templateWith(after));

    assertTrue(delta.getNonDestructiveChanges().isEmpty(), delta.toString());
    assertEquals(1, delta.getDestructiveChanges().size(), delta.toString());
    Change reported = delta.getDestructiveChanges().get(0);
    assertInstanceOf(ConstraintChange.class, reported);
    assertEquals("Notes", reported.getFieldName());
    assertTrue(((ConstraintChange) reported).isDestructive());
  }

  static Stream<Arguments> choiceChanges() {
    return Stream.of(
        Arguments.of("loses an option",
            ListField.builder().withName("Colour").withOption("Red").withOption("Blue").build(),
            ListField.builder().withName("Colour").withOption("Red").build()),
        Arguments.of("becomes multiple choice",
            ListField.builder().withName("Colour").withOption("Red").withOption("Blue").withMultipleChoice(false).build(),
            ListField.builder().withName("Colour").withOption("Red").withOption("Blue").withMultipleChoice(true).build()));
  }

  /** A stored choice may no longer be offered, or may no longer have the shape the field expects. */
  @ParameterizedTest(name = "a list field that {0}")
  @MethodSource("choiceChanges")
  public void aChangeToTheChoicesOnOfferIsReported(String change, FieldSchemaArtifact before,
                                                   FieldSchemaArtifact after) {
    Delta delta = new DeltaFinder().findDelta(templateWith(before), templateWith(after));

    List<Change> reported = Stream.concat(delta.getDestructiveChanges().stream(),
        delta.getNonDestructiveChanges().stream()).toList();
    assertEquals(1, reported.size(), delta.toString());
    assertInstanceOf(ConstraintChange.class, reported.get(0));
    assertEquals("Colour", reported.get(0).getFieldName());
  }

  /** A template holding the one field, read back as the resource server reads a stored template. */
  private static TemplateSchemaArtifact templateWith(FieldSchemaArtifact field) {
    TemplateSchemaArtifact template = TemplateSchemaArtifact.builder()
        .withName("Constraint change fixture")
        .withFieldSchema(field)
        .build();
    return new JsonArtifactReader().readTemplateSchemaArtifact(
        new JsonArtifactRenderer().renderTemplateSchemaArtifact(template));
  }
}
