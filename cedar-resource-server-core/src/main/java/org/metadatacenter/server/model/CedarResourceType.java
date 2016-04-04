package org.metadatacenter.server.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum CedarResourceType {
  FOLDER("folder"),
  FIELD("field"),
  ELEMENT("element"),
  TEMPLATE("template"),
  INSTANCE("instance");

  private final String value;

  CedarResourceType(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }

  public static CedarResourceType forValue(String s) {
    for (CedarResourceType t : values()) {
      if (t.getValue().equals(s)) {
        return t;
      }
    }
    return null;
  }
}