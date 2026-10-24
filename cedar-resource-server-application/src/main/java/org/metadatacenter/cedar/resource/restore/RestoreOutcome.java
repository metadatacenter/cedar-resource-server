package org.metadatacenter.cedar.resource.restore;

import org.metadatacenter.cedar.resource.artifact.ArtifactServerRefusal;

/**
 * What one attempt to put an artifact's document back did.
 *
 * <p>A write whose graph update does not commit is undone by putting the document back, and the failure
 * answered for the write says what became of the document. This is what the attempt knows of it.
 *
 * @param state what became of the document
 * @param etag  the document's ETag after it was put back, or null when the attempt did not put it back
 *              or the answer carried none
 */
public record RestoreOutcome(State state, String etag) {

  public enum State {
    /** The document is back to what it was before the write. */
    PUT_BACK,
    /** A later write or a deletion has replaced the document since, and what it left stands. */
    OVERTAKEN,
    /** The put-back did not succeed, and the restore outbox holds it for the relay to retry. */
    RECORDED,
    /**
     * The artifact server refused the put-back, and the restore outbox holds it for a person, since asking
     * again would be refused again. The document still holds the write.
     */
    PARKED,
    /** No put-back was pending, because the relay or a committed graph update had settled it first. */
    SETTLED,
    /** The put-back did not succeed and nothing recorded it, so the document still holds the write. */
    NOT_PUT_BACK
  }

  /**
   * The outcome a put-back's answer amounts to.
   *
   * <p>A 412 or a 404 means that the document is no longer the one the put-back undoes, so what replaced
   * it stands. The exception is the 412 a template's put-back receives when instances now reference the
   * template and the put-back would give it other children. Nothing replaced the document then, and it
   * still holds the write. Any other refusal leaves it holding the write as well.
   *
   * @param recorded whether the restore outbox holds the put-back, which it then keeps: for the relay to
   *                 retry after an outage, and for a person after a refusal on its merits
   */
  public static RestoreOutcome answered(int status, String etag, String body, boolean recorded) {
    if (status == 200 || status == 201) {
      return new RestoreOutcome(State.PUT_BACK, etag);
    }
    if (ArtifactServerRefusal.refusesForInstances(status, body)) {
      return new RestoreOutcome(recorded ? State.PARKED : State.NOT_PUT_BACK, null);
    }
    if (status == 404 || status == 412) {
      return new RestoreOutcome(State.OVERTAKEN, null);
    }
    if (!recorded) {
      return new RestoreOutcome(State.NOT_PUT_BACK, null);
    }
    return new RestoreOutcome(status >= 400 && status < 500 ? State.PARKED : State.RECORDED, null);
  }
}
