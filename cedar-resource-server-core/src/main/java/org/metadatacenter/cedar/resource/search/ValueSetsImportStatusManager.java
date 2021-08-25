package org.metadatacenter.cedar.resource.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ValueSetsImportStatusManager
{
  final static Logger logger = LoggerFactory.getLogger(ValueSetsImportStatusManager.class);

  public enum ImportStatus
  {
    NOT_YET_INITIATED, IN_PROGRESS, COMPLETE, ERROR
  }

  private ImportStatus importStatus;

  private static ValueSetsImportStatusManager singleInstance;

  private ValueSetsImportStatusManager()
  {
    importStatus = ImportStatus.NOT_YET_INITIATED;
  }

  public static synchronized ValueSetsImportStatusManager getInstance()
  {
    if (singleInstance == null) {
      singleInstance = new ValueSetsImportStatusManager();
    }
    return singleInstance;
  }

  public ImportStatus getImportStatus() { return importStatus; }

  public synchronized void setImportStatus(ImportStatus importStatus) {
    this.importStatus = importStatus;
  }
}