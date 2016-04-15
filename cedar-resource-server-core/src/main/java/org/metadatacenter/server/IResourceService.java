package org.metadatacenter.server;

import com.github.fge.jsonschema.core.exceptions.ProcessingException;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.metadatacenter.model.CedarResource;

import java.io.IOException;

public interface IResourceService {

  public CedarResource createResource(@NonNull CedarResource resource) throws IOException, ProcessingException;

  public CedarResource findResource(@NonNull String id) throws IOException, ProcessingException;

  public CedarResource findFolder(@NonNull String path);
}
