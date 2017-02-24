package org.metadatacenter.cedar.resource.resources;

import org.metadatacenter.cedar.util.dw.CedarMicroserviceIndexResource;
import org.metadatacenter.config.CedarConfig;


public class IndexResource extends CedarMicroserviceIndexResource {

  public IndexResource(CedarConfig cedarConfig) {
    super(cedarConfig, "CEDAR Resource Server");
  }
}