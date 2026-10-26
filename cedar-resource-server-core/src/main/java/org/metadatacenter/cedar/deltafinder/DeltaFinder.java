package org.metadatacenter.cedar.deltafinder;

import org.metadatacenter.artifacts.model.core.ChildSchemaArtifact;
import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.core.fields.TemporalGranularity;
import org.metadatacenter.artifacts.model.core.fields.XsdTemporalDatatype;
import org.metadatacenter.artifacts.model.core.fields.constraints.ControlledTermValueConstraints;
import org.metadatacenter.artifacts.model.core.fields.constraints.LiteralValueConstraint;
import org.metadatacenter.artifacts.model.core.fields.constraints.ValueConstraints;
import org.metadatacenter.artifacts.model.core.fields.constraints.ValueConstraintsActionType;
import org.metadatacenter.cedar.deltafinder.change.*;

import java.math.BigDecimal;
import java.util.*;

public class DeltaFinder {

  public Delta findDelta(TemplateSchemaArtifact oldTemplate, TemplateSchemaArtifact newTemplate) {
    Delta delta = new Delta();
    compareSchemas(oldTemplate.fieldSchemas(), oldTemplate.elementSchemas(),
        newTemplate.fieldSchemas(), newTemplate.elementSchemas(),
        oldTemplate.templateUi().order(), newTemplate.templateUi().order(),
        delta);
    return delta;
  }

  private void compareSchemas(Map<String, FieldSchemaArtifact> oldFields,
                              Map<String, ElementSchemaArtifact> oldElements,
                              Map<String, FieldSchemaArtifact> newFields,
                              Map<String, ElementSchemaArtifact> newElements,
                              List<String> oldOrder,
                              List<String> newOrder,
                              Delta delta) {

    // Step 1: Detect Renames
    Set<String> matchedOldKeys = new HashSet<>();
    Set<String> matchedNewKeys = new HashSet<>();
    Map<String, String> renamedFields = new LinkedHashMap<>();

    // A field renamed and changed at once keeps its kind. A removed field is matched first to a new field that is
    // the same but for its name, and only then to one of the same kind, so that renaming two fields at once does
    // not swap their values. A new field takes the values of one removed field at most.
    for (boolean sameButForName : new boolean[]{true, false}) {
      for (String oldKey : oldFields.keySet()) {
        if (newFields.containsKey(oldKey) || matchedOldKeys.contains(oldKey)) {
          continue;
        }
        FieldSchemaArtifact oldField = oldFields.get(oldKey);
        for (String newKey : newFields.keySet()) {
          if (oldFields.containsKey(newKey) || matchedNewKeys.contains(newKey)) {
            continue;
          }

          FieldSchemaArtifact newField = newFields.get(newKey);
          if (oldField.getClass().equals(newField.getClass()) &&
              oldField.fieldUi().inputType() == newField.fieldUi().inputType() &&
              (!sameButForName || (Objects.equals(oldField.fieldUi(), newField.fieldUi()) &&
                  areValueConstraintsEqual(oldField, newField)))) {

            matchedOldKeys.add(oldKey);
            matchedNewKeys.add(newKey);
            renamedFields.put(oldKey, newKey);
            delta.addNonDestructiveChange(new Rename(oldKey, newKey));
            break;
          }
        }
      }
    }

    // Step 1b: Detect Renames in Elements
    Map<String, String> renamedElements = new LinkedHashMap<>();
    for (String oldKey : oldElements.keySet()) {
      if (newElements.containsKey(oldKey)) continue;
      ElementSchemaArtifact oldElement = oldElements.get(oldKey);

      for (String newKey : newElements.keySet()) {
        if (oldElements.containsKey(newKey) || matchedNewKeys.contains(newKey)) continue;

        ElementSchemaArtifact newElement = newElements.get(newKey);

        // Compare inner structure: field sets and schemas
        if (Objects.equals(oldElement.fieldSchemas(), newElement.fieldSchemas()) &&
            Objects.equals(oldElement.elementSchemas(), newElement.elementSchemas()) &&
            Objects.equals(oldElement.getUi(), newElement.getUi())) {

          matchedOldKeys.add(oldKey);
          matchedNewKeys.add(newKey);
          renamedElements.put(oldKey, newKey);
          delta.addNonDestructiveChange(new Rename(oldKey, newKey));
          break;
        }
      }
    }


    // Step 2: Additions
    for (String newKey : newFields.keySet()) {
      if (!oldFields.containsKey(newKey) && !matchedNewKeys.contains(newKey)) {
        delta.addNonDestructiveChange(new Addition(newKey, "field"));
      }
    }

    for (String newKey : newElements.keySet()) {
      if (!oldElements.containsKey(newKey) && !matchedNewKeys.contains(newKey)) {
        delta.addNonDestructiveChange(new Addition(newKey, "element"));
      }
    }

    // Step 3: Deletions
    for (String oldKey : oldFields.keySet()) {
      if (!newFields.containsKey(oldKey) && !matchedOldKeys.contains(oldKey)) {
        delta.addDestructiveChange(new Deletion(oldKey, "field"));
      }
    }

    for (String oldKey : oldElements.keySet()) {
      if (!newElements.containsKey(oldKey) && !matchedOldKeys.contains(oldKey)) {
        delta.addDestructiveChange(new Deletion(oldKey, "element"));
      }
    }

    // Step 4: Field changes for common fields (not renamed)
    Set<String> commonKeys = new HashSet<>(oldFields.keySet());
    commonKeys.retainAll(newFields.keySet());
    for (String key : commonKeys) {
      if (matchedOldKeys.contains(key) || matchedNewKeys.contains(key)) {
        continue;
      }

      FieldSchemaArtifact oldField = oldFields.get(key);
      FieldSchemaArtifact newField = newFields.get(key);

      // The model's own input type, not the implementing class. getSimpleName() reported the
      // package-private record behind the interface — "TextFieldRecord" for a field the model, the
      // JSON and every other surface call "textfield" — so the delta named a type no caller could
      // look up and that changes whenever the implementation is renamed.
      String oldType = oldField.fieldUi().inputType().getText();
      String newType = newField.fieldUi().inputType().getText();
      // Any change of field type is destructive: the stored values were written against the old type.
      if (!oldType.equals(newType)) {
        delta.addDestructiveChange(new TypeChange(key, oldType, newType, true));
      }

      detectFieldChange(oldField, newField, key, delta);
    }

    // Step 4b: Field changes for renamed fields, whose stored values move to the new name
    renamedFields.forEach((oldKey, newKey) ->
        detectFieldChange(oldFields.get(oldKey), newFields.get(newKey), newKey, delta));

    // Step 5: Recursively handle elements
    Set<String> commonElementKeys = new HashSet<>(oldElements.keySet());
    commonElementKeys.retainAll(newElements.keySet());
    for (String key : commonElementKeys) {
      ElementSchemaArtifact oldElem = oldElements.get(key);
      ElementSchemaArtifact newElem = newElements.get(key);
      detectElementChange(oldElem, newElem, key, delta);
      compareSchemas(
          oldElem.fieldSchemas(), oldElem.elementSchemas(),
          newElem.fieldSchemas(), newElem.elementSchemas(),
          oldElem.getUi().order(), newElem.getUi().order(),
          delta
      );
    }

    // Step 5b: Changes to renamed elements, whose stored values move to the new name. A renamed element has the
    // same contents as before, so there is nothing inside it to compare.
    renamedElements.forEach((oldKey, newKey) ->
        detectElementChange(oldElements.get(oldKey), newElements.get(newKey), newKey, delta));

    // Step 6: Order change detection
    List<String> filteredOldOrder = new ArrayList<>();
    for (String field : oldOrder) {
      if (newFields.containsKey(field) || newElements.containsKey(field)) {
        filteredOldOrder.add(field);
      }
    }

    List<String> filteredNewOrder = new ArrayList<>();
    for (String field : newOrder) {
      if (oldFields.containsKey(field) || oldElements.containsKey(field)) {
        filteredNewOrder.add(field);
      }
    }

    // Every position the order lost or gained is explained by a rename already reported, so an
    // order change on top of it would say the same thing twice.
    boolean orderExplainedByRenames = !matchedOldKeys.isEmpty()
        && filteredOldOrder.size() == filteredNewOrder.size()
        && matchedOldKeys.size() == filteredOldOrder.size();

    if (!filteredOldOrder.equals(filteredNewOrder) && !orderExplainedByRenames) {
      if (isSpecialRename(oldFields, newFields, oldOrder, newOrder)) {
        String[] rename = detectFieldRename(oldFields, newFields);
        delta.addNonDestructiveChange(new SpecialRename(rename[0], rename[1]));
      } else {
        delta.addNonDestructiveChange(new OrderChange("Field order changed"));
      }
    }

  }


  private boolean isSpecialRename(Map<String, FieldSchemaArtifact> oldFields,
                                  Map<String, FieldSchemaArtifact> newFields,
                                  List<String> oldOrder,
                                  List<String> newOrder) {
    if (oldOrder.size() != newOrder.size()) {
      return false;
    }

    for (int i = 0; i < oldOrder.size(); i++) {
      String oldKey = oldOrder.get(i);
      String newKey = newOrder.get(i);
      if (!Objects.equals(oldKey, newKey)) {
        FieldSchemaArtifact oldF = oldFields.get(oldKey);
        FieldSchemaArtifact newF = newFields.get(newKey);
        if (oldF == null || newF == null || !oldF.equals(newF)) {
          return false;
        }
      }
    }

    return true;
  }

  private String[] detectFieldRename(Map<String, FieldSchemaArtifact> oldFields,
                                     Map<String, FieldSchemaArtifact> newFields) {
    for (Map.Entry<String, FieldSchemaArtifact> oldEntry : oldFields.entrySet()) {
      for (Map.Entry<String, FieldSchemaArtifact> newEntry : newFields.entrySet()) {
        if (!oldEntry.getKey().equals(newEntry.getKey()) &&
            oldEntry.getValue().equals(newEntry.getValue())) {
          return new String[]{oldEntry.getKey(), newEntry.getKey()};
        }
      }
    }
    return new String[]{"unknown", "unknown"};
  }

  private boolean areValueConstraintsEqual(FieldSchemaArtifact oldField, FieldSchemaArtifact newField) {
    Optional<ValueConstraints> oldVCOpt = oldField.valueConstraints();
    Optional<ValueConstraints> newVCOpt = newField.valueConstraints();

    if (oldVCOpt.isEmpty() && newVCOpt.isEmpty()) {
      return true;
    }
    if (oldVCOpt.isEmpty() || newVCOpt.isEmpty()) {
      return false;
    }

    ValueConstraints oldVC = oldVCOpt.get();
    ValueConstraints newVC = newVCOpt.get();

    // 1. Check if the types match
    if (!oldVC.getClass().equals(newVC.getClass())) {
      return false;
    }

    // 2. Compare base fields
    boolean baseFieldsEqual =
        oldVC.requiredValue() == newVC.requiredValue() &&
            oldVC.recommendedValue() == newVC.recommendedValue() &&
            oldVC.multipleChoice() == newVC.multipleChoice() &&
            Objects.equals(oldVC.defaultValue(), newVC.defaultValue());

    if (!baseFieldsEqual) {
      return false;
    }

    // 3. If TextValueConstraints, compare minLength, maxLength, regex
    if (oldVC.isTextValueConstraint() && newVC.isTextValueConstraint()) {
      var oldTextVC = oldVC.asTextValueConstraints();
      var newTextVC = newVC.asTextValueConstraints();

      return Objects.equals(oldTextVC.minLength(), newTextVC.minLength()) &&
          Objects.equals(oldTextVC.maxLength(), newTextVC.maxLength()) &&
          Objects.equals(oldTextVC.regex(), newTextVC.regex()) &&
          Objects.equals(oldTextVC.literals(), newTextVC.literals());
    }

    // 4. Otherwise compare every constraint the type has. A numeric, temporal or controlled-term field was
    // once assumed unchanged here, so a field renamed and constrained at once was reported as a rename alone.
    return oldVC.equals(newVC);
  }

  /**
   * Reports every change to a field, other than its type and its name, as one change under the name it now has. It is
   * destructive when a value stored under the old field can fail the new one. A renamed field's property IRI is
   * compared, as a renamed element's is. The instance clone carries a renamed field's values to its new name only by
   * an unchanged IRI.
   */
  private void detectFieldChange(FieldSchemaArtifact oldField,
                                 FieldSchemaArtifact newField,
                                 String key,
                                 Delta delta) {
    List<String> changes = new ArrayList<>();
    boolean destructive = detectCardinalityChange(oldField, newField, changes);
    destructive |= detectPropertyChange(oldField, newField, changes);

    if (oldField.fieldUi().isTemporal() && newField.fieldUi().isTemporal()) {
      var oldUi = oldField.fieldUi().asTemporalFieldUi();
      var newUi = newField.fieldUi().asTemporalFieldUi();

      if (oldUi.temporalGranularity() != newUi.temporalGranularity()) {
        changes.add("temporalGranularity changed");
        destructive |= !admitsEveryValueOf(oldUi.temporalGranularity(), newUi.temporalGranularity(),
            temporalType(oldField));
      }
      boolean oldTimezoneEnabled = oldUi.timezoneEnabled().orElse(false);
      boolean newTimezoneEnabled = newUi.timezoneEnabled().orElse(false);
      if (oldTimezoneEnabled != newTimezoneEnabled) {
        changes.add("timezoneEnabled changed");
        // A stored value may carry a timezone offset
        destructive |= !newTimezoneEnabled;
      }
    }

    destructive |= detectValueConstraintsChange(oldField, newField, changes);
    reportChanges(key, changes, destructive, delta);
  }

  /**
   * Reports every change to an element, other than its contents and its name, as one change under the name it now
   * has. It is destructive when an element stored under the old template can fail the new one. A renamed element's
   * property IRI is compared too. Both template editors keep a child's property IRI when they rename it, and the
   * instance clone that follows a new version carries a renamed child's values to its new name only by that IRI. An
   * element renamed with a new IRI therefore leaves its stored values under a name the template no longer declares.
   */
  private static void detectElementChange(ElementSchemaArtifact oldElement, ElementSchemaArtifact newElement,
                                          String key, Delta delta) {
    List<String> changes = new ArrayList<>();
    boolean destructive = detectCardinalityChange(oldElement, newElement, changes);
    destructive |= detectPropertyChange(oldElement, newElement, changes);
    reportChanges(key, changes, destructive, delta);
  }

  /**
   * Records a change to how many values a field or an element holds, and returns whether a stored instance can
   * fail it. A cardinality bound that narrows is destructive, and one that widens is not.
   */
  private static boolean detectCardinalityChange(ChildSchemaArtifact oldChild, ChildSchemaArtifact newChild,
                                                 List<String> changes) {
    if (oldChild.isMultiple() != newChild.isMultiple()) {
      changes.add("isMultiple changed");
      // A stored value is one value where a list is now expected, or the other way round
      return true;
    }
    boolean destructive = false;
    // An absent lower bound means zero
    int oldMinItems = oldChild.minItems().orElse(ChildSchemaArtifact.DEFAULT_MIN_ITEMS);
    int newMinItems = newChild.minItems().orElse(ChildSchemaArtifact.DEFAULT_MIN_ITEMS);
    if (oldMinItems != newMinItems) {
      changes.add("minItems changed");
      destructive |= newMinItems > oldMinItems;
    }
    if (!Objects.equals(oldChild.maxItems(), newChild.maxItems())) {
      changes.add("maxItems changed");
      destructive |= lowersUpperBound(oldChild.maxItems(), newChild.maxItems());
    }
    return destructive;
  }

  /** Records a change to the property IRI of a field or an element, renamed or not. Any change is destructive. */
  private static boolean detectPropertyChange(ChildSchemaArtifact oldChild, ChildSchemaArtifact newChild,
                                              List<String> changes) {
    if (Objects.equals(oldChild.propertyUri(), newChild.propertyUri())) {
      return false;
    }
    changes.add("propertyUri changed");
    // A stored instance maps the child's name to the property IRI it was written with. The template's context
    // admits only the IRI it declares, requires it, and refuses a mapping it does not declare.
    return true;
  }

  private static void reportChanges(String key, List<String> changes, boolean destructive, Delta delta) {
    if (!changes.isEmpty()) {
      if (destructive) {
        delta.addDestructiveChange(new ConstraintChange(key, "Changed: " + String.join(", ", changes), "", true));
      } else {
        delta.addNonDestructiveChange(new ConstraintChange(key, "Changed: " + String.join(", ", changes), "", false));
      }
    }
  }

  /** Records each change to a field's value constraints, and returns whether a stored value can fail it. */
  private boolean detectValueConstraintsChange(FieldSchemaArtifact oldField,
                                               FieldSchemaArtifact newField,
                                               List<String> changes) {
    Optional<ValueConstraints> oldVCOpt = oldField.valueConstraints();
    Optional<ValueConstraints> newVCOpt = newField.valueConstraints();

    if (oldVCOpt.isEmpty() && newVCOpt.isEmpty()) {
      return false; // No constraint changes
    }
    if (oldVCOpt.isEmpty() || newVCOpt.isEmpty()) {
      changes.add("Presence/absence of valueConstraints");
      return true;
    }

    ValueConstraints oldVC = oldVCOpt.get();
    ValueConstraints newVC = newVCOpt.get();

    if (!oldVC.getClass().equals(newVC.getClass())) {
      changes.add("Constraint type changed");
      return true;
    }

    boolean destructive = false;

    // A change is destructive when a value stored under the old constraints can fail the new ones.
    // Base fields
    if (oldVC.requiredValue() != newVC.requiredValue()) {
      changes.add("requiredValue changed");
      // A stored instance may hold no value
      destructive |= newVC.requiredValue();
    }
    if (oldVC.recommendedValue() != newVC.recommendedValue()) {
      changes.add("recommendedValue changed");
    }
    if (oldVC.multipleChoice() != newVC.multipleChoice()) {
      changes.add("multipleChoice changed");
      // A stored value is one value where a list is now expected, or the other way round
      destructive = true;
    }

    // Default value (safe)
    if (!Objects.equals(oldVC.defaultValue(), newVC.defaultValue())) {
      changes.add("defaultValue changed");
    }

    // Text-specific
    if (oldVC.isTextValueConstraint() && newVC.isTextValueConstraint()) {
      var oldTextVC = oldVC.asTextValueConstraints();
      var newTextVC = newVC.asTextValueConstraints();

      if (!Objects.equals(oldTextVC.minLength(), newTextVC.minLength())) {
        changes.add("minLength changed");
        destructive |= raisesLowerBound(oldTextVC.minLength(), newTextVC.minLength());
      }
      if (!Objects.equals(oldTextVC.maxLength(), newTextVC.maxLength())) {
        changes.add("maxLength changed");
        destructive |= lowersUpperBound(oldTextVC.maxLength(), newTextVC.maxLength());
      }
      if (!Objects.equals(oldTextVC.regex(), newTextVC.regex())) {
        changes.add("regex changed");
        destructive |= newTextVC.regex().isPresent();
      }
      if (!Objects.equals(oldTextVC.literals(), newTextVC.literals())) {
        changes.add("literals changed");
        // A stored choice may no longer be offered
        destructive |= !newTextVC.literals().isEmpty()
            && (oldTextVC.literals().isEmpty() || !labels(newTextVC.literals()).containsAll(labels(oldTextVC.literals())));
      }
    }

    // Numeric-specific. A stored value carries its datatype, so any change of datatype fails it.
    if (oldVC.isNumericValueConstraint() && newVC.isNumericValueConstraint()) {
      var oldNumericVC = oldVC.asNumericValueConstraints();
      var newNumericVC = newVC.asNumericValueConstraints();

      if (oldNumericVC.numberType() != newNumericVC.numberType()) {
        changes.add("numberType changed");
        destructive = true;
      }
      if (!Objects.equals(oldNumericVC.minValue(), newNumericVC.minValue())) {
        changes.add("minValue changed");
        destructive |= raisesLowerBound(oldNumericVC.minValue(), newNumericVC.minValue());
      }
      if (!Objects.equals(oldNumericVC.maxValue(), newNumericVC.maxValue())) {
        changes.add("maxValue changed");
        destructive |= lowersUpperBound(oldNumericVC.maxValue(), newNumericVC.maxValue());
      }
      if (!Objects.equals(oldNumericVC.decimalPlace(), newNumericVC.decimalPlace())) {
        changes.add("decimalPlace changed");
        destructive |= lowersUpperBound(oldNumericVC.decimalPlace(), newNumericVC.decimalPlace());
      }
      if (!Objects.equals(oldNumericVC.unitOfMeasure(), newNumericVC.unitOfMeasure())) {
        changes.add("unitOfMeasure changed");
      }
    }

    // Temporal-specific. A stored value carries its datatype, so any change of datatype fails it.
    if (oldVC.isTemporalValueConstraint() && newVC.isTemporalValueConstraint()
        && oldVC.asTemporalValueConstraints().temporalType() != newVC.asTemporalValueConstraints().temporalType()) {
      changes.add("temporalType changed");
      destructive = true;
    }

    // Controlled-term-specific. A stored term may lie outside every value space the field now offers. A value
    // space that changes in any way counts as one taken away, and a field that offers none accepts any term.
    if (oldVC.isControlledTermValueConstraint() && newVC.isControlledTermValueConstraint()) {
      var oldTermVC = oldVC.asControlledTermValueConstraints();
      var newTermVC = newVC.asControlledTermValueConstraints();
      boolean oldOffersAnyTerm = offersAnyTerm(oldTermVC);
      boolean newOffersAnyTerm = offersAnyTerm(newTermVC);

      if (!Objects.equals(oldTermVC.ontologies(), newTermVC.ontologies())) {
        changes.add("ontologies changed");
        destructive |= !newOffersAnyTerm && (oldOffersAnyTerm || !newTermVC.ontologies().containsAll(oldTermVC.ontologies()));
      }
      if (!Objects.equals(oldTermVC.valueSets(), newTermVC.valueSets())) {
        changes.add("valueSets changed");
        destructive |= !newOffersAnyTerm && (oldOffersAnyTerm || !newTermVC.valueSets().containsAll(oldTermVC.valueSets()));
      }
      if (!Objects.equals(oldTermVC.classes(), newTermVC.classes())) {
        changes.add("classes changed");
        destructive |= !newOffersAnyTerm && (oldOffersAnyTerm || !newTermVC.classes().containsAll(oldTermVC.classes()));
      }
      if (!Objects.equals(oldTermVC.branches(), newTermVC.branches())) {
        changes.add("branches changed");
        destructive |= !newOffersAnyTerm && (oldOffersAnyTerm || !newTermVC.branches().containsAll(oldTermVC.branches()));
      }
      if (!Objects.equals(oldTermVC.actions(), newTermVC.actions())) {
        changes.add("actions changed");
        // A new deletion takes a term out of a value set
        destructive |= newTermVC.actions().stream().anyMatch(action ->
            action.action() == ValueConstraintsActionType.DELETE && !oldTermVC.actions().contains(action));
      }
    }

    return destructive;
  }

  /** The temporal datatype a field's stored values carry. A field that states none may hold a date and a time. */
  private static XsdTemporalDatatype temporalType(FieldSchemaArtifact field) {
    return field.valueConstraints()
        .filter(ValueConstraints::isTemporalValueConstraint)
        .map(constraints -> constraints.asTemporalValueConstraints().temporalType())
        .orElse(XsdTemporalDatatype.DATETIME);
  }

  /**
   * Whether every value the old granularity admits also meets the new one. The validator checks the date and the
   * time of a value apart. A coarser date granularity admits fewer dates. A time granularity either rounds a time,
   * to midnight, a whole hour or a whole minute, and lets it leave its seconds out, or requires its seconds, with a
   * fraction or without one. A finer rounding admits every time a coarser one does, and no other change of time
   * granularity keeps every stored time valid.
   */
  private static boolean admitsEveryValueOf(TemporalGranularity oldGranularity, TemporalGranularity newGranularity,
                                            XsdTemporalDatatype type) {
    boolean datesAdmitted = type == XsdTemporalDatatype.TIME
        || dateLatitude(oldGranularity) <= dateLatitude(newGranularity);
    boolean timesAdmitted = type == XsdTemporalDatatype.DATE
        || oldGranularity == newGranularity
        || (roundedTimeLatitude(oldGranularity) >= 0
            && roundedTimeLatitude(oldGranularity) <= roundedTimeLatitude(newGranularity));
    return datesAdmitted && timesAdmitted;
  }

  /** How freely a granularity lets a date vary: only January 1, only the first of a month, or any day. */
  private static int dateLatitude(TemporalGranularity granularity) {
    return switch (granularity) {
      case YEAR -> 0;
      case MONTH -> 1;
      default -> 2;
    };
  }

  /**
   * How freely a granularity lets a rounded time vary: only midnight, any whole hour, or any whole minute. A
   * granularity of seconds rounds nothing and gives -1.
   */
  private static int roundedTimeLatitude(TemporalGranularity granularity) {
    return switch (granularity) {
      case YEAR, MONTH, DAY -> 0;
      case HOUR -> 1;
      case MINUTE -> 2;
      case SECOND, DECIMAL_SECOND -> -1;
    };
  }

  /** Whether a lower bound now refuses a value the old one admitted: it is new, or it is higher. */
  private static boolean raisesLowerBound(Optional<? extends Number> oldBound, Optional<? extends Number> newBound) {
    return newBound.isPresent()
        && (oldBound.isEmpty() || decimal(newBound.get()).compareTo(decimal(oldBound.get())) > 0);
  }

  /** Whether an upper bound now refuses a value the old one admitted: it is new, or it is lower. */
  private static boolean lowersUpperBound(Optional<? extends Number> oldBound, Optional<? extends Number> newBound) {
    return newBound.isPresent()
        && (oldBound.isEmpty() || decimal(newBound.get()).compareTo(decimal(oldBound.get())) < 0);
  }

  private static BigDecimal decimal(Number number) {
    return new BigDecimal(number.toString());
  }

  private static List<String> labels(List<LiteralValueConstraint> literals) {
    return literals.stream().map(LiteralValueConstraint::label).toList();
  }

  private static boolean offersAnyTerm(ControlledTermValueConstraints constraints) {
    return constraints.ontologies().isEmpty() && constraints.valueSets().isEmpty()
        && constraints.classes().isEmpty() && constraints.branches().isEmpty();
  }


}
