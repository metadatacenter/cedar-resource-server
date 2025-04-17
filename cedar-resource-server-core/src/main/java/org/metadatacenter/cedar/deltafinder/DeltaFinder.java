package org.metadatacenter.cedar.deltafinder;

import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
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
      if (newFields.containsKey(oldKey)) continue;
      FieldSchemaArtifact oldField = oldFields.get(oldKey);
      for (String newKey : newFields.keySet()) {
        if (oldFields.containsKey(newKey)) continue;

        FieldSchemaArtifact newField = newFields.get(newKey);
        if (oldField.getClass().equals(newField.getClass()) &&
            Objects.equals(oldField.fieldUi(), newField.fieldUi()) &&
            Objects.equals(oldField.valueConstraints(), newField.valueConstraints())) {

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
        delta.addNonDestructiveChange(new Addition(newKey));
      }
    }

    for (String newKey : newElements.keySet()) {
      if (!oldElements.containsKey(newKey)) {
        delta.addNonDestructiveChange(new Addition(newKey));
      }
    }

    // Step 3: Deletions
    for (String oldKey : oldFields.keySet()) {
      if (!newFields.containsKey(oldKey) && !matchedOldKeys.contains(oldKey)) {
        delta.addDestructiveChange(new Deletion(oldKey));
      }
    }

    for (String oldKey : oldElements.keySet()) {
      if (!newElements.containsKey(oldKey)) {
        delta.addDestructiveChange(new Deletion(oldKey));
      }
    }

    // Step 4: Field changes for common fields (not renamed)
    Set<String> commonKeys = new HashSet<>(oldFields.keySet());
    commonKeys.retainAll(newFields.keySet());
    for (String key : commonKeys) {
      if (matchedOldKeys.contains(key) || matchedNewKeys.contains(key)) continue;

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

      String oldConstraints = oldField.fieldUi().inputType().getText();
      String newConstraints = newField.fieldUi().inputType().getText();
      if (!oldConstraints.equals(newConstraints)) {
        boolean destructive = isDestructiveConstraintChange(oldConstraints, newConstraints);
        if (destructive) {
          delta.addDestructiveChange(new ConstraintChange(key, oldConstraints, newConstraints, true));
        } else {
          delta.addNonDestructiveChange(new ConstraintChange(key, oldConstraints, newConstraints, false));
        }
      }
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
    Set<String> incompatible = Set.of("string->int", "float->int");
    return incompatible.contains(oldType.toLowerCase() + "->" + newType.toLowerCase());
  }

  private boolean isDestructiveConstraintChange(String oldC, String newC) {
    return oldC.length() < newC.length();
  }

  private boolean isSpecialRename(Map<String, FieldSchemaArtifact> oldFields,
                                  Map<String, FieldSchemaArtifact> newFields,
                                  List<String> oldOrder,
                                  List<String> newOrder) {
    if (oldOrder.size() != newOrder.size()) return false;

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
}
