package org.metadatacenter.server.dao;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.metadatacenter.server.model.CedarResource;

import java.io.IOException;

public interface GenericResourceDao {

  @NonNull CedarResource create(@NonNull CedarResource resource) throws IOException;

  CedarResource find(@NonNull String resourceId) throws IOException;
}
