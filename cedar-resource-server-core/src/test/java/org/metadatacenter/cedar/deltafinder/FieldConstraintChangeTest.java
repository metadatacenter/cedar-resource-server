package org.metadatacenter.cedar.deltafinder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.metadatacenter.artifacts.model.core.ChildSchemaArtifact;
import org.metadatacenter.artifacts.model.core.ControlledTermField;
import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.core.LinkField;
import org.metadatacenter.artifacts.model.core.ListField;
import org.metadatacenter.artifacts.model.core.NumericField;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TemporalField;
import org.metadatacenter.artifacts.model.core.TextField;
import org.metadatacenter.artifacts.model.core.fields.TemporalGranularity;
import org.metadatacenter.artifacts.model.core.fields.XsdNumericDatatype;
import org.metadatacenter.artifacts.model.core.fields.XsdTemporalDatatype;
import org.metadatacenter.artifacts.model.core.fields.constraints.ValueConstraintsActionType;
import org.metadatacenter.artifacts.model.core.fields.constraints.ValueType;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.cedar.deltafinder.change.Change;
import org.metadatacenter.cedar.deltafinder.change.ConstraintChange;
import org.metadatacenter.cedar.deltafinder.change.Deletion;
import org.metadatacenter.cedar.deltafinder.change.Rename;

import java.net.URI;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A template with instances is updated in place only when its delta is empty. A change to the constraints
 * of a field that keeps its name must therefore be reported, whatever kind of field it is, or the stored
 * instances fall out of step with their template. So must a change to how many values a field or an element
 * holds, to the granularity or timezone of a temporal field, and to the property IRI of a field or an element.
 * The delta then classes the change as destructive when a value stored under the old template can fail the new
 * one. A field or an element renamed and changed at once is reported as a rename and as a change to what it is now
 * called.
 */
public class FieldConstraintChangeTest {

  private static final URI DESCRIPTION = URI.create("http://purl.org/dc/terms/description");
  private static final URI COMMENT = URI.create("http://www.w3.org/2000/01/rdf-schema#comment");
  private static final URI MATERIAL_SAMPLE = URI.create("http://purl.obolibrary.org/obo/OBI_0000747");
  private static final URI SPECIMEN = URI.create("http://purl.obolibrary.org/obo/OBI_0100051");
  private static final URI DOID = URI.create("https://data.bioontology.org/ontologies/DOID");
  private static final URI NCIT = URI.create("https://data.bioontology.org/ontologies/NCIT");
  private static final URI DISEASE = URI.create("http://purl.obolibrary.org/obo/DOID_4");
  private static final URI CANCER = URI.create("http://purl.obolibrary.org/obo/DOID_162");
  private static final URI VALUE_SET = URI.create("https://cadsr.nci.nih.gov/metadata/CADSR-VS/ae9ba5ab");

  static Stream<Arguments> changesAStoredValueMayFail() {
    return Stream.of(
        Arguments.of("a text field made required",
            TextField.builder().withName("Notes").build(),
            TextField.builder().withName("Notes").withRequiredValue(true).build()),
        Arguments.of("a text field given a pattern",
            TextField.builder().withName("Notes").build(),
            TextField.builder().withName("Notes").withRegex("[A-Z]+").build()),
        Arguments.of("a text field whose pattern changes",
            TextField.builder().withName("Notes").withRegex("[A-Z]+").build(),
            TextField.builder().withName("Notes").withRegex("[a-z]+").build()),
        Arguments.of("a text field whose minimum length rises",
            TextField.builder().withName("Notes").withMinLength(2).build(),
            TextField.builder().withName("Notes").withMinLength(5).build()),
        Arguments.of("a text field whose maximum length falls",
            TextField.builder().withName("Notes").withMaxLength(20).build(),
            TextField.builder().withName("Notes").withMaxLength(10).build()),
        Arguments.of("a text field that becomes multi-instance",
            TextField.builder().withName("Notes").build(),
            TextField.builder().withName("Notes").withIsMultiple(true).build()),
        Arguments.of("a list field that loses an option",
            ListField.builder().withName("Colour").withOption("Red").withOption("Blue").build(),
            ListField.builder().withName("Colour").withOption("Red").build()),
        Arguments.of("a list field whose option is renamed",
            ListField.builder().withName("Colour").withOption("Red").withOption("Blue").build(),
            ListField.builder().withName("Colour").withOption("Red").withOption("Navy").build()),
        Arguments.of("a list field made multiple choice",
            ListField.builder().withName("Colour").withOption("Red").withOption("Blue").withMultipleChoice(false).build(),
            ListField.builder().withName("Colour").withOption("Red").withOption("Blue").withMultipleChoice(true).build()),
        Arguments.of("a numeric field whose minimum rises",
            age().withMinValue(0).build(),
            age().withMinValue(18).build()),
        Arguments.of("a numeric field given a maximum",
            age().build(),
            age().withMaxValue(65).build()),
        Arguments.of("a numeric field allowing fewer decimal places",
            age().withDecimalPlaces(2).build(),
            age().withDecimalPlaces(0).build()),
        Arguments.of("a numeric field changed from decimal to integer",
            age().build(),
            age().withNumericType(XsdNumericDatatype.INT).build()),
        Arguments.of("a numeric field changed from integer to decimal",
            age().withNumericType(XsdNumericDatatype.INT).build(),
            age().build()),
        Arguments.of("a temporal field changed from date to date and time",
            visit().withTemporalType(XsdTemporalDatatype.DATE).build(),
            visit().withTemporalType(XsdTemporalDatatype.DATETIME).build()),
        Arguments.of("a controlled term field whose ontology is replaced",
            ControlledTermField.builder().withName("Disease").withOntologyValueConstraint(DOID, "DOID", "Human Disease Ontology").build(),
            ControlledTermField.builder().withName("Disease").withOntologyValueConstraint(NCIT, "NCIT", "NCI Thesaurus").build()),
        Arguments.of("a controlled term field that loses a class",
            ControlledTermField.builder().withName("Disease")
                .withClassValueConstraint(DISEASE, "DOID", "disease", "disease", ValueType.ONTOLOGY_CLASS)
                .withClassValueConstraint(CANCER, "DOID", "cancer", "cancer", ValueType.ONTOLOGY_CLASS).build(),
            ControlledTermField.builder().withName("Disease")
                .withClassValueConstraint(DISEASE, "DOID", "disease", "disease", ValueType.ONTOLOGY_CLASS).build()),
        Arguments.of("a controlled term field whose branch is made shallower",
            ControlledTermField.builder().withName("Disease")
                .withBranchValueConstraint(DISEASE, "Human Disease Ontology (DOID)", "DOID", "disease", 3).build(),
            ControlledTermField.builder().withName("Disease")
                .withBranchValueConstraint(DISEASE, "Human Disease Ontology (DOID)", "DOID", "disease", 1).build()),
        Arguments.of("a controlled term field that loses a value set",
            ControlledTermField.builder().withName("Disease")
                .withOntologyValueConstraint(DOID, "DOID", "Human Disease Ontology")
                .withValueSetValueConstraint(VALUE_SET, "CADSR-VS", "Disease Type").build(),
            ControlledTermField.builder().withName("Disease")
                .withOntologyValueConstraint(DOID, "DOID", "Human Disease Ontology").build()),
        Arguments.of("an unconstrained controlled term field given an ontology",
            ControlledTermField.builder().withName("Disease").build(),
            ControlledTermField.builder().withName("Disease").withOntologyValueConstraint(DOID, "DOID", "Human Disease Ontology").build()),
        Arguments.of("a controlled term field that removes a term from its value set",
            ControlledTermField.builder().withName("Disease")
                .withValueSetValueConstraint(VALUE_SET, "CADSR-VS", "Disease Type").build(),
            ControlledTermField.builder().withName("Disease")
                .withValueSetValueConstraint(VALUE_SET, "CADSR-VS", "Disease Type")
                .withValueConstraintsAction(CANCER, "CADSR-VS", ValueType.VALUE, ValueConstraintsActionType.DELETE,
                    VALUE_SET, 0).build()),
        Arguments.of("a multi-instance field whose minimum count rises",
            repeatedNotes().withMinItems(1).build(),
            repeatedNotes().withMinItems(2).build()),
        Arguments.of("a multi-instance field given a maximum count",
            repeatedNotes().build(),
            repeatedNotes().withMaxItems(3).build()),
        Arguments.of("a multi-instance field whose maximum count falls",
            repeatedNotes().withMaxItems(5).build(),
            repeatedNotes().withMaxItems(3).build()),
        Arguments.of("a date field whose granularity becomes the month",
            visit().withTemporalType(XsdTemporalDatatype.DATE).build(),
            visit().withTemporalType(XsdTemporalDatatype.DATE).withTemporalGranularity(TemporalGranularity.MONTH).build()),
        // A value at minute granularity may leave its seconds out, and one at second granularity must state them
        Arguments.of("a date and time field whose granularity becomes the second",
            visit().withTemporalType(XsdTemporalDatatype.DATETIME).withTemporalGranularity(TemporalGranularity.MINUTE).build(),
            visit().withTemporalType(XsdTemporalDatatype.DATETIME).withTemporalGranularity(TemporalGranularity.SECOND).build()),
        Arguments.of("a time field whose seconds must now carry a fraction",
            visit().withTemporalType(XsdTemporalDatatype.TIME).withTemporalGranularity(TemporalGranularity.SECOND).build(),
            visit().withTemporalType(XsdTemporalDatatype.TIME).withTemporalGranularity(TemporalGranularity.DECIMAL_SECOND).build()),
        Arguments.of("a temporal field whose timezone is disabled",
            appointment().withTimeZoneEnabled(true).build(),
            appointment().withTimeZoneEnabled(false).build()),
        Arguments.of("a field whose property IRI is replaced",
            TextField.builder().withName("Notes").withPropertyUri(DESCRIPTION).build(),
            TextField.builder().withName("Notes").withPropertyUri(COMMENT).build()),
        Arguments.of("a field given a property IRI",
            TextField.builder().withName("Notes").build(),
            TextField.builder().withName("Notes").withPropertyUri(DESCRIPTION).build()),
        Arguments.of("a field whose property IRI is removed",
            TextField.builder().withName("Notes").withPropertyUri(DESCRIPTION).build(),
            TextField.builder().withName("Notes").build()),
        Arguments.of("an element that becomes multi-instance",
            sample().build(),
            sample().withIsMultiple(true).build()),
        Arguments.of("a multi-instance element made single",
            sample().withIsMultiple(true).build(),
            sample().build()),
        Arguments.of("a multi-instance element whose minimum count rises",
            sample().withIsMultiple(true).withMinItems(0).build(),
            sample().withIsMultiple(true).withMinItems(1).build()),
        Arguments.of("a multi-instance element whose maximum count falls",
            sample().withIsMultiple(true).withMaxItems(4).build(),
            sample().withIsMultiple(true).withMaxItems(2).build()),
        // A stored instance maps the element's name to the property IRI it was written with
        Arguments.of("an element whose property IRI is replaced",
            sample().withPropertyUri(MATERIAL_SAMPLE).build(),
            sample().withPropertyUri(SPECIMEN).build()),
        Arguments.of("an element given a property IRI",
            sample().build(),
            sample().withPropertyUri(MATERIAL_SAMPLE).build()),
        Arguments.of("an element whose property IRI is removed",
            sample().withPropertyUri(MATERIAL_SAMPLE).build(),
            sample().build()));
  }

  /** A stored instance may hold no value, or one the new rule refuses, so each change is destructive. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("changesAStoredValueMayFail")
  public void aConstraintStoredValuesMayFailIsADestructiveChange(String change, ChildSchemaArtifact before,
                                                                ChildSchemaArtifact after) {
    Delta delta = findDelta(before, after);

    assertTrue(delta.getNonDestructiveChanges().isEmpty(), delta.toString());
    assertOneConstraintChange(delta.getDestructiveChanges(), before.name(), true);
  }

  static Stream<Arguments> changesNoStoredValueCanFail() {
    return Stream.of(
        Arguments.of("a text field made optional",
            TextField.builder().withName("Notes").withRequiredValue(true).build(),
            TextField.builder().withName("Notes").build()),
        Arguments.of("a text field whose pattern is removed",
            TextField.builder().withName("Notes").withRegex("[A-Z]+").build(),
            TextField.builder().withName("Notes").build()),
        Arguments.of("a text field whose maximum length rises",
            TextField.builder().withName("Notes").withMaxLength(10).build(),
            TextField.builder().withName("Notes").withMaxLength(20).build()),
        Arguments.of("a list field that gains an option",
            ListField.builder().withName("Colour").withOption("Red").build(),
            ListField.builder().withName("Colour").withOption("Red").withOption("Blue").build()),
        Arguments.of("a numeric field whose minimum falls",
            age().withMinValue(18).build(),
            age().withMinValue(0).build()),
        Arguments.of("a numeric field whose maximum is removed",
            age().withMaxValue(65).build(),
            age().build()),
        Arguments.of("a numeric field whose unit changes",
            age().withUnitOfMeasure("years").build(),
            age().withUnitOfMeasure("months").build()),
        Arguments.of("a temporal field given a default",
            visit().withTemporalType(XsdTemporalDatatype.DATE).build(),
            visit().withTemporalType(XsdTemporalDatatype.DATE)
                .withDefaultValue("2026-01-01").build()),
        Arguments.of("a controlled term field that gains an ontology",
            ControlledTermField.builder().withName("Disease").withOntologyValueConstraint(DOID, "DOID", "Human Disease Ontology").build(),
            ControlledTermField.builder().withName("Disease").withOntologyValueConstraint(DOID, "DOID", "Human Disease Ontology")
                .withOntologyValueConstraint(NCIT, "NCIT", "NCI Thesaurus").build()),
        Arguments.of("a link field given a default",
            LinkField.builder().withName("Homepage").build(),
            LinkField.builder().withName("Homepage").withDefaultValue(URI.create("https://example.org/")).build()),
        Arguments.of("a multi-instance field whose minimum count falls",
            repeatedNotes().withMinItems(2).build(),
            repeatedNotes().withMinItems(1).build()),
        Arguments.of("a multi-instance field whose maximum count rises",
            repeatedNotes().withMaxItems(3).build(),
            repeatedNotes().withMaxItems(5).build()),
        Arguments.of("a multi-instance field whose maximum count is removed",
            repeatedNotes().withMaxItems(3).build(),
            repeatedNotes().build()),
        Arguments.of("a date field whose granularity becomes the day",
            visit().withTemporalType(XsdTemporalDatatype.DATE).withTemporalGranularity(TemporalGranularity.MONTH).build(),
            visit().withTemporalType(XsdTemporalDatatype.DATE).build()),
        Arguments.of("a date and time field whose granularity becomes the minute",
            visit().withTemporalType(XsdTemporalDatatype.DATETIME).withTemporalGranularity(TemporalGranularity.HOUR).build(),
            visit().withTemporalType(XsdTemporalDatatype.DATETIME).withTemporalGranularity(TemporalGranularity.MINUTE).build()),
        Arguments.of("a temporal field whose timezone is enabled",
            appointment().build(),
            appointment().withTimeZoneEnabled(true).build()),
        Arguments.of("a multi-instance element whose minimum count falls",
            sample().withIsMultiple(true).withMinItems(1).build(),
            sample().withIsMultiple(true).withMinItems(0).build()),
        Arguments.of("a multi-instance element whose maximum count is removed",
            sample().withIsMultiple(true).withMaxItems(2).build(),
            sample().withIsMultiple(true).build()));
  }

  /** A change every stored value still satisfies is reported, so the update is still seen, but as safe. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("changesNoStoredValueCanFail")
  public void aConstraintEveryStoredValueSatisfiesIsANonDestructiveChange(String change, ChildSchemaArtifact before,
                                                                         ChildSchemaArtifact after) {
    Delta delta = findDelta(before, after);

    assertTrue(delta.getDestructiveChanges().isEmpty(), delta.toString());
    assertOneConstraintChange(delta.getNonDestructiveChanges(), before.name(), false);
  }

  /**
   * An absent lower bound means zero, so stating zero changes nothing. The JSON form always states the
   * bound, so the templates are compared as built rather than read back.
   */
  @Test
  public void aLowerBoundOfZeroIsTheBoundAnAbsentOneMeans() {
    Delta delta = new DeltaFinder().findDelta(
        TemplateSchemaArtifact.builder().withName("Constraint change fixture")
            .withFieldSchema(repeatedNotes().build()).build(),
        TemplateSchemaArtifact.builder().withName("Constraint change fixture")
            .withFieldSchema(repeatedNotes().withMinItems(0).build()).build());

    assertTrue(delta.getDestructiveChanges().isEmpty(), delta.toString());
    assertTrue(delta.getNonDestructiveChanges().isEmpty(), delta.toString());
  }

  static Stream<Arguments> renamesThatNarrowTheField() {
    return Stream.of(
        Arguments.of("a numeric field renamed as its minimum rises",
            age().withMinValue(0).build(),
            age().withName("Age in years").withMinValue(18).build()),
        Arguments.of("a text field renamed and given a pattern",
            TextField.builder().withName("Notes").build(),
            TextField.builder().withName("Code").withRegex("[A-Z]+").build()),
        Arguments.of("a text field renamed and made multi-instance",
            TextField.builder().withName("Notes").build(),
            TextField.builder().withName("Comments").withIsMultiple(true).build()),
        // The same field but for its name and its count, so the old one is matched to it before any other
        Arguments.of("a multi-instance field renamed as its maximum count falls",
            repeatedNotes().withMaxItems(5).build(),
            repeatedNotes().withName("Comments").withMaxItems(3).build()),
        Arguments.of("a date field renamed as its granularity becomes the month",
            visit().withTemporalType(XsdTemporalDatatype.DATE).build(),
            visit().withName("Visit month").withTemporalType(XsdTemporalDatatype.DATE)
                .withTemporalGranularity(TemporalGranularity.MONTH).build()),
        Arguments.of("a controlled term field renamed as its ontology is replaced",
            ControlledTermField.builder().withName("Disease").withOntologyValueConstraint(DOID, "DOID", "Human Disease Ontology").build(),
            ControlledTermField.builder().withName("Condition").withOntologyValueConstraint(NCIT, "NCIT", "NCI Thesaurus").build()));
  }

  /**
   * A stored value moves to the field's new name with the rename, and may then fail the renamed field. The rename
   * alone would let the template be read as unchanged but for a name.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("renamesThatNarrowTheField")
  public void aFieldRenamedAndNarrowedAtOnceIsARenameAndADestructiveChange(String change, FieldSchemaArtifact before,
                                                                          FieldSchemaArtifact after) {
    Delta delta = findDelta(before, after);

    assertEquals(1, delta.getNonDestructiveChanges().size(), delta.toString());
    assertRename(delta.getNonDestructiveChanges().get(0), before.name(), after.name());
    assertOneConstraintChange(delta.getDestructiveChanges(), after.name(), true);
  }

  @Test
  public void aFieldRenamedAndWidenedAtOnceIsARenameAndASafeChange() {
    Delta delta = findDelta(age().withMinValue(18).build(), age().withName("Age in years").withMinValue(0).build());

    assertTrue(delta.getDestructiveChanges().isEmpty(), delta.toString());
    List<Change> changes = delta.getNonDestructiveChanges();
    assertEquals(2, changes.size(), delta.toString());
    assertRename(changes.get(0), "Age", "Age in years");
    assertOneConstraintChange(changes.subList(1, 2), "Age in years", false);
  }

  /**
   * The instance clone that follows a new version moves a renamed field's stored values to its new name only when
   * its property IRI is unchanged. A field renamed as its IRI is replaced therefore leaves its values under a name
   * the template no longer declares. This rename was once expected to be a rename alone, on the belief that the
   * template editor gives a renamed field a new IRI. Both template editors keep the IRI instead, as they do for an
   * element, so a replaced IRI comes from an edit of the IRI and is destructive.
   */
  @Test
  public void aFieldRenamedAsItsPropertyIriIsReplacedIsARenameAndADestructiveChange() {
    Delta delta = findDelta(TextField.builder().withName("Notes").withPropertyUri(DESCRIPTION).build(),
        TextField.builder().withName("Comments").withPropertyUri(COMMENT).build());

    assertEquals(1, delta.getNonDestructiveChanges().size(), delta.toString());
    assertRename(delta.getNonDestructiveChanges().get(0), "Notes", "Comments");
    assertOneConstraintChange(delta.getDestructiveChanges(), "Comments", true);
    assertEquals("Changed: propertyUri changed",
        ((ConstraintChange) delta.getDestructiveChanges().get(0)).getOldConstraint());
  }

  /** Both template editors keep a renamed field's property IRI, so a rename that keeps it is a rename alone. */
  @Test
  public void aFieldRenamedWithItsPropertyIriIsARenameAlone() {
    Delta delta = findDelta(TextField.builder().withName("Notes").withPropertyUri(DESCRIPTION).build(),
        TextField.builder().withName("Comments").withPropertyUri(DESCRIPTION).build());

    assertTrue(delta.getDestructiveChanges().isEmpty(), delta.toString());
    assertEquals(1, delta.getNonDestructiveChanges().size(), delta.toString());
    assertRename(delta.getNonDestructiveChanges().get(0), "Notes", "Comments");
  }

  static Stream<Arguments> renamesThatNarrowTheElement() {
    return Stream.of(
        Arguments.of("an element renamed and made multi-instance",
            sample().build(),
            sample().withName("Specimen").withIsMultiple(true).build()),
        Arguments.of("a multi-instance element renamed and made single",
            sample().withIsMultiple(true).build(),
            sample().withName("Specimen").build()),
        Arguments.of("a multi-instance element renamed as its minimum count rises",
            sample().withIsMultiple(true).withMinItems(0).build(),
            sample().withName("Specimen").withIsMultiple(true).withMinItems(1).build()),
        Arguments.of("a multi-instance element renamed as its maximum count falls",
            sample().withIsMultiple(true).withMaxItems(4).build(),
            sample().withName("Specimen").withIsMultiple(true).withMaxItems(2).build()),
        Arguments.of("a multi-instance element renamed and given a maximum count",
            sample().withIsMultiple(true).build(),
            sample().withName("Specimen").withIsMultiple(true).withMaxItems(2).build()),
        // The instance clone carries a renamed element's values to its new name by the property IRI they share
        Arguments.of("an element renamed as its property IRI is replaced",
            sample().withPropertyUri(MATERIAL_SAMPLE).build(),
            sample().withName("Specimen").withPropertyUri(SPECIMEN).build()));
  }

  /**
   * A renamed element is paired with its old self by its contents alone, and its stored values move to its new name.
   * A count the renamed element no longer admits can then refuse them, so the rename alone would hide the change.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("renamesThatNarrowTheElement")
  public void anElementRenamedAndNarrowedAtOnceIsARenameAndADestructiveChange(String change,
                                                                             ElementSchemaArtifact before,
                                                                             ElementSchemaArtifact after) {
    Delta delta = findDelta(before, after);

    assertEquals(1, delta.getNonDestructiveChanges().size(), delta.toString());
    assertRename(delta.getNonDestructiveChanges().get(0), before.name(), after.name());
    assertOneConstraintChange(delta.getDestructiveChanges(), after.name(), true);
  }

  static Stream<Arguments> renamesThatWidenTheElement() {
    return Stream.of(
        Arguments.of("a multi-instance element renamed as its minimum count falls",
            sample().withIsMultiple(true).withMinItems(1).build(),
            sample().withName("Specimen").withIsMultiple(true).withMinItems(0).build()),
        Arguments.of("a multi-instance element renamed as its maximum count rises",
            sample().withIsMultiple(true).withMaxItems(2).build(),
            sample().withName("Specimen").withIsMultiple(true).withMaxItems(4).build()),
        Arguments.of("a multi-instance element renamed as its maximum count is removed",
            sample().withIsMultiple(true).withMaxItems(2).build(),
            sample().withName("Specimen").withIsMultiple(true).build()));
  }

  /** A count every stored element still meets is reported with the rename, but as safe. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("renamesThatWidenTheElement")
  public void anElementRenamedAndWidenedAtOnceIsARenameAndASafeChange(String change, ElementSchemaArtifact before,
                                                                     ElementSchemaArtifact after) {
    Delta delta = findDelta(before, after);

    assertTrue(delta.getDestructiveChanges().isEmpty(), delta.toString());
    List<Change> changes = delta.getNonDestructiveChanges();
    assertEquals(2, changes.size(), delta.toString());
    assertRename(changes.get(0), before.name(), after.name());
    assertOneConstraintChange(changes.subList(1, 2), after.name(), false);
  }

  /** Both template editors keep a renamed element's property IRI, so a rename that keeps it is a rename alone. */
  @Test
  public void anElementRenamedWithItsPropertyIriIsARenameAlone() {
    Delta delta = findDelta(sample().withIsMultiple(true).withMaxItems(2).withPropertyUri(MATERIAL_SAMPLE).build(),
        sample().withName("Specimen").withIsMultiple(true).withMaxItems(2).withPropertyUri(MATERIAL_SAMPLE).build());

    assertTrue(delta.getDestructiveChanges().isEmpty(), delta.toString());
    assertEquals(1, delta.getNonDestructiveChanges().size(), delta.toString());
    assertRename(delta.getNonDestructiveChanges().get(0), "Sample", "Specimen");
  }

  /**
   * A removed field is matched first to a new field that is the same but for its name, and only then to one of the
   * same kind, so two fields renamed at once each keep their own values.
   */
  @Test
  public void aRemovedFieldIsMatchedFirstToANewFieldTheSameButForItsName() {
    Delta delta = new DeltaFinder().findDelta(
        templateWith(TextField.builder().withName("Notes").build(),
            TextField.builder().withName("Code").withRequiredValue(true).build()),
        templateWith(TextField.builder().withName("Identifier").withRequiredValue(true).build(),
            TextField.builder().withName("Comments").build()));

    assertTrue(delta.getDestructiveChanges().isEmpty(), delta.toString());
    List<Change> changes = delta.getNonDestructiveChanges();
    assertEquals(2, changes.size(), delta.toString());
    assertRename(changes.get(0), "Notes", "Comments");
    assertRename(changes.get(1), "Code", "Identifier");
  }

  /** A new field takes the values of one removed field, so a second removed field like it is a deletion. */
  @Test
  public void aNewFieldTakesTheValuesOfOneRemovedFieldOnly() {
    Delta delta = new DeltaFinder().findDelta(
        templateWith(TextField.builder().withName("Notes").build(), TextField.builder().withName("Remarks").build()),
        templateWith(TextField.builder().withName("Comments").build()));

    assertEquals(1, delta.getNonDestructiveChanges().size(), delta.toString());
    assertRename(delta.getNonDestructiveChanges().get(0), "Notes", "Comments");
    assertEquals(1, delta.getDestructiveChanges().size(), delta.toString());
    Change deletion = delta.getDestructiveChanges().get(0);
    assertInstanceOf(Deletion.class, deletion);
    assertEquals("Remarks", deletion.getFieldName());
  }

  /** A temporal field needs a granularity, and a day suits a date and a date and time alike. */
  private static TemporalField.TemporalFieldBuilder visit() {
    return TemporalField.builder().withName("Visit").withTemporalGranularity(TemporalGranularity.DAY);
  }

  /** A timezone applies only to a time, so the model drops it from a field whose granularity is a date. */
  private static TemporalField.TemporalFieldBuilder appointment() {
    return TemporalField.builder().withName("Appointment").withTemporalType(XsdTemporalDatatype.DATETIME)
        .withTemporalGranularity(TemporalGranularity.MINUTE);
  }

  /** A numeric field needs a datatype. Decimal is the one a field gets when it states none. */
  private static NumericField.NumericFieldBuilder age() {
    return NumericField.builder().withName("Age").withNumericType(XsdNumericDatatype.DECIMAL);
  }

  private static TextField.TextFieldBuilder repeatedNotes() {
    return TextField.builder().withName("Notes").withIsMultiple(true);
  }

  private static ElementSchemaArtifact.Builder sample() {
    return ElementSchemaArtifact.builder().withName("Sample")
        .withFieldSchema(TextField.builder().withName("Notes").build());
  }

  private static void assertOneConstraintChange(List<Change> changes, String fieldName, boolean destructive) {
    assertEquals(1, changes.size(), changes.toString());
    Change reported = changes.get(0);
    assertInstanceOf(ConstraintChange.class, reported);
    assertEquals(fieldName, reported.getFieldName());
    assertEquals(destructive, reported.isDestructive());
  }

  private static void assertRename(Change change, String oldName, String newName) {
    Rename rename = assertInstanceOf(Rename.class, change);
    assertEquals(oldName, rename.getFieldName());
    assertEquals(newName, rename.getNewFieldName());
  }

  private static Delta findDelta(ChildSchemaArtifact before, ChildSchemaArtifact after) {
    return new DeltaFinder().findDelta(templateWith(before), templateWith(after));
  }

  /** A template holding the fields and elements, read back as the resource server reads a stored template. */
  private static TemplateSchemaArtifact templateWith(ChildSchemaArtifact... children) {
    TemplateSchemaArtifact.Builder builder = TemplateSchemaArtifact.builder().withName("Constraint change fixture");
    for (ChildSchemaArtifact child : children) {
      if (child instanceof ElementSchemaArtifact element) builder.withElementSchema(element);
      if (child instanceof FieldSchemaArtifact field) builder.withFieldSchema(field);
    }
    TemplateSchemaArtifact template = builder.build();
    return new JsonArtifactReader().readTemplateSchemaArtifact(
        new JsonArtifactRenderer().renderTemplateSchemaArtifact(template));
  }
}
