package org.metadatacenter.cedar.deltafinder;

import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.core.fields.constraints.ValueConstraints;
import org.metadatacenter.cedar.deltafinder.change.*;

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

    Set<String> oldKeys = new HashSet<>();
    oldKeys.addAll(oldFields.keySet());
    oldKeys.addAll(oldElements.keySet());

    Set<String> newKeys = new HashSet<>();
    newKeys.addAll(newFields.keySet());
    newKeys.addAll(newElements.keySet());

    // Step 1: Detect Renames
    Map<String, String> renamedFields = new HashMap<>();
    Set<String> matchedOldKeys = new HashSet<>();
    Set<String> matchedNewKeys = new HashSet<>();

    for (String oldKey : oldFields.keySet()) {
      if (newFields.containsKey(oldKey)) {
        continue;
      }
      FieldSchemaArtifact oldField = oldFields.get(oldKey);
      for (String newKey : newFields.keySet()) {
        if (oldFields.containsKey(newKey)) {
          continue;
        }

        FieldSchemaArtifact newField = newFields.get(newKey);
        if (oldField.getClass().equals(newField.getClass()) &&
            Objects.equals(oldField.fieldUi(), newField.fieldUi()) &&
            areValueConstraintsEqual(oldField, newField)) {

          renamedFields.put(oldKey, newKey);
          matchedOldKeys.add(oldKey);
          matchedNewKeys.add(newKey);
          delta.addNonDestructiveChange(new Rename(oldKey, newKey));
          break;
        }
      }
    }

    // Step 1b: Detect Renames in Elements
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

          renamedFields.put(oldKey, newKey);
          matchedOldKeys.add(oldKey);
          matchedNewKeys.add(newKey);
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

      String oldType = oldField.getClass().getSimpleName();
      String newType = newField.getClass().getSimpleName();
      if (!oldType.equals(newType)) {
        boolean destructive = isDestructiveTypeChange(oldType, newType);
        if (destructive) {
          delta.addDestructiveChange(new TypeChange(key, oldType, newType, true));
        } else {
          delta.addNonDestructiveChange(new TypeChange(key, oldType, newType, false));
        }
      }

      detectValueConstraintsChange(oldField, newField, key, delta);
    }

    // Step 5: Recursively handle elements
    Set<String> commonElementKeys = new HashSet<>(oldElements.keySet());
    commonElementKeys.retainAll(newElements.keySet());
    for (String key : commonElementKeys) {
      ElementSchemaArtifact oldElem = oldElements.get(key);
      ElementSchemaArtifact newElem = newElements.get(key);
      compareSchemas(
          oldElem.fieldSchemas(), oldElem.elementSchemas(),
          newElem.fieldSchemas(), newElem.elementSchemas(),
          oldElem.getUi().order(), newElem.getUi().order(),
          delta
      );
    }

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

    if (!filteredOldOrder.equals(filteredNewOrder)) {
      // If all changes are renames and no unmatched fields exist, suppress OrderChange
      if (!matchedOldKeys.isEmpty() && filteredOldOrder.size() == filteredNewOrder.size() && matchedOldKeys.size() == filteredOldOrder.size()) {
        // All fields were renamed — order change is redundant
        // No action needed
      } else if (isSpecialRename(oldFields, newFields, oldOrder, newOrder)) {
        String[] rename = detectFieldRename(oldFields, newFields);
        delta.addNonDestructiveChange(new SpecialRename(rename[0], rename[1]));
      } else {
        delta.addNonDestructiveChange(new OrderChange("Field order changed"));
      }
    }

  }


  private boolean isDestructiveTypeChange(String oldType, String newType) {
    if (oldType.equals(newType)) {
      return false;
    }
    return true;
  }

  private boolean isDestructiveConstraintChange(String oldC, String newC) {
    return oldC.length() < newC.length();
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

    // 4. Otherwise, if not TextValueConstraints, assume they are equal now
    return true;
  }

  private void detectValueConstraintsChange(FieldSchemaArtifact oldField,
                                            FieldSchemaArtifact newField,
                                            String key,
                                            Delta delta) {
    Optional<ValueConstraints> oldVCOpt = oldField.valueConstraints();
    Optional<ValueConstraints> newVCOpt = newField.valueConstraints();

    if (oldVCOpt.isEmpty() && newVCOpt.isEmpty()) {
      return; // No constraint changes
    }
    if (oldVCOpt.isEmpty() || newVCOpt.isEmpty()) {
      delta.addDestructiveChange(new ConstraintChange(key, "Presence/absence of valueConstraints", "Presence/absence " +
          "of valueConstraints", true));
      return;
    }

    ValueConstraints oldVC = oldVCOpt.get();
    ValueConstraints newVC = newVCOpt.get();

    if (!oldVC.getClass().equals(newVC.getClass())) {
      delta.addDestructiveChange(new ConstraintChange(key, "Constraint type changed", "Constraint type changed", true));
      return;
    }

    boolean destructive = false;
    List<String> changes = new ArrayList<>();

    // Base fields
    if (oldVC.requiredValue() != newVC.requiredValue()) {
      changes.add("requiredValue changed");
      destructive = true;
    }
    if (oldVC.recommendedValue() != newVC.recommendedValue()) {
      changes.add("recommendedValue changed");
    }
    if (oldVC.multipleChoice() != newVC.multipleChoice()) {
      changes.add("multipleChoice changed");
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
        destructive = true;
      }
      if (!Objects.equals(oldTextVC.maxLength(), newTextVC.maxLength())) {
        changes.add("maxLength changed");
        destructive = true;
      }
      if (!Objects.equals(oldTextVC.regex(), newTextVC.regex())) {
        changes.add("regex changed");
        destructive = true;
      }
      if (!Objects.equals(oldTextVC.literals(), newTextVC.literals())) {
        changes.add("literals changed");
      }
    }

    if (!changes.isEmpty()) {
      if (destructive) {
        delta.addDestructiveChange(new ConstraintChange(key, "Changed: " + String.join(", ", changes), "", true));
      } else {
        delta.addNonDestructiveChange(new ConstraintChange(key, "Changed: " + String.join(", ", changes), "", false));
      }
    }
  }


}
