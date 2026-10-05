package org.metadatacenter.cedar.resource.deletion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.metadatacenter.util.json.JsonMapper;

/**
 * A fixed, optimistic deletion plan. Inventory is unfiltered; the public projection redacts unreadable
 * names/identifiers. A changed confirmation deletes nothing. Execution stops at the first failure and
 * reports partial progress: multiple databases cannot provide an atomic recursive delete.
 */
public final class RecursiveFolderDeletion {
  /** previousVersionId is the version this one was made from, or null when it has none. */
  public record Entry(String id, String name, String type, String parentId, int depth,
                      long graphRevision, String etag, boolean readable, boolean deletable,
                      boolean protectedFolder, String previousVersionId) {}
  public record Inventory(List<Entry> entries, Map<String, List<String>> references) {}
  public record Item(String id, String name, String type, String parentId, int depth,
                     boolean deletable, boolean protectedFolder, long instancesInside, long instancesOutside) {}
  public record Plan(String token, boolean allowed, Map<String, Long> counts, List<Item> items,
                     long restrictedItems, long protectedFolders, long templatesWithInstances,
                     long templatesWithOutsideInstances, long instancesOutside) {}
  /** Stable protocol codes let hosts localise every operational message. */
  public enum Reason {
    NOT_OWNER("Only the owner of this folder can delete it and its contents."),
    PROTECTED_ROOT("Home and system folders cannot be deleted."),
    INVALID_TOKEN("A current deletion inventory token is required."),
    INVENTORY_UNAVAILABLE("The complete inventory could not be checked. Refresh and try again."),
    CHANGED("The folder changed. Review a new inventory before deleting."),
    BLOCKED("The folder cannot be deleted. Review the blockers."),
    STOPPED("Deletion stopped. Refresh the inventory to check what remains."),
    ITEM_CHANGED("An item changed or its permission changed. Refresh the inventory before continuing."),
    FOLDER_CHANGED("A folder changed or gained new contents. Refresh the inventory before continuing."),
    CLEANUP_PENDING("Artifact cleanup is pending. Refresh the inventory before continuing."),
    ARTIFACT_REFUSED("An artifact could not be deleted. Refresh the inventory to check permissions and template references."),
    COMPLETED("Deletion completed.");
    private final String message;
    Reason(String message) { this.message = message; }
    public String code() { return "FOLDER_DELETE_" + name(); }
    public String message() { return message; }
  }
  public record Step(boolean completed, Reason reason) {}
  public record Outcome(String status, Map<String, Long> deleted, long remaining, String message, String code) {
    public Outcome(String status, Map<String, Long> deleted, long remaining, Reason reason) {
      this(status, deleted, remaining, reason.message(), reason.code());
    }
  }
  public interface Store {
    Inventory inventory() throws Exception;
    /** Must recheck permission, location and revision, then use revision-qualified deletion. */
    Step delete(Entry entry) throws Exception;
  }
  private static final List<String> TYPES = List.of("folder", "template", "element", "field", "instance");
  private final Store store;
  private final String userId;
  private final String rootId;
  public RecursiveFolderDeletion(Store store, String userId, String rootId) {
    this.store = store;
    this.userId = userId;
    this.rootId = rootId;
  }
  public Plan plan() throws Exception { return plan(store.inventory()); }
  private Plan plan(Inventory inventory) throws Exception {
    TreeMap<String, Entry> entries = new TreeMap<>();
    for (Entry entry : inventory.entries()) {
      if (entries.putIfAbsent(entry.id(), entry) != null || !TYPES.contains(entry.type()))
        throw new IllegalStateException("The folder inventory is inconsistent; nothing can be deleted");
    }
    if (!entries.containsKey(rootId)) throw new IllegalStateException("The selected folder no longer exists");
    Set<String> instances = new HashSet<>();
    entries.values().stream().filter(e -> e.type().equals("instance")).forEach(e -> instances.add(e.id()));
    Map<String, Long> counts = counts();
    List<Item> items = new ArrayList<>();
    long restricted = 0, protectedFolders = 0, referenced = 0, blockedTemplates = 0, outsideCount = 0;
    TreeMap<String, List<String>> references = new TreeMap<>();
    for (Entry entry : entries.values()) {
      counts.compute(entry.type(), (k, v) -> v + 1);
      if (!entry.deletable()) restricted++;
      if (entry.protectedFolder()) protectedFolders++;
      long inside = 0, outside = 0;
      if (entry.type().equals("template")) {
        List<String> found = inventory.references().get(entry.id());
        if (found == null || found.stream().anyMatch(Objects::isNull))
          throw new IllegalStateException("Template references could not be fully checked");
        List<String> ids = found.stream().distinct().sorted().toList();
        references.put(entry.id(), ids);
        inside = ids.stream().filter(instances::contains).count();
        outside = ids.size() - inside;
        if (!ids.isEmpty()) referenced++;
        if (outside > 0) blockedTemplates++;
        outsideCount += outside;
      }
      items.add(new Item(entry.readable() ? entry.id() : null, entry.readable() ? entry.name() : null,
          entry.type(), entry.readable() && entries.containsKey(entry.parentId()) && entries.get(entry.parentId()).readable()
              ? entry.parentId() : null,
          entry.depth(), entry.deletable(), entry.protectedFolder(), inside, outside));
    }
    // Include topology, both revisions, authorization and the actual reference identifiers; counts
    // alone would not detect a same-size replacement or an instance moved outside the tree.
    byte[] snapshot = JsonMapper.STRICT_MAPPER.writeValueAsBytes(List.of(userId, rootId, entries, references));
    String token = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(snapshot));
    return new Plan(token, restricted == 0 && protectedFolders == 0 && blockedTemplates == 0,
        counts, items, restricted, protectedFolders, referenced, blockedTemplates, outsideCount);
  }
  public Outcome execute(String token) throws Exception {
    Inventory inventory = store.inventory();
    Plan current = plan(inventory);
    Map<String, Long> deleted = counts();
    if (token == null || !MessageDigest.isEqual(current.token().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8)))
      return new Outcome("changed", deleted, inventory.entries().size(), Reason.CHANGED);
    if (!current.allowed()) return new Outcome("blocked", deleted, inventory.entries().size(), Reason.BLOCKED);
    Map<String, Integer> successors = successorsInside(inventory.entries());
    List<Entry> ordered = new ArrayList<>(inventory.entries());
    ordered.sort(Comparator.comparingInt((Entry e) -> e.type().equals("instance") ? 0 : e.type().equals("folder") ? 2 : 1)
        .thenComparingInt(e -> successors.getOrDefault(e.id(), 0))
        .thenComparing(Comparator.comparingInt(Entry::depth).reversed()).thenComparing(Entry::id));
    long remaining = ordered.size();
    for (Entry entry : ordered) {
      Step step;
      try { step = store.delete(entry); }
      catch (Exception e) {
        // Do not claim rollback or continue after an ambiguous network result. Re-inventory is the
        // only safe next action; the existing per-artifact outbox owns any pending store cleanup.
        return new Outcome("stopped", deleted, remaining, Reason.STOPPED);
      }
      if (!step.completed()) return new Outcome("stopped", deleted, remaining, step.reason());
      deleted.compute(entry.type(), (k, v) -> v + 1);
      remaining--;
    }
    return new Outcome("completed", deleted, 0, Reason.COMPLETED);
  }
  /**
   * How many newer versions of each artifact the folder holds, along its longest chain of successors.
   * Deleting a version rewrites its successor's link to it, which moves the successor's revision past
   * the one the inventory confirmed. Deleting in ascending order of this count removes every
   * successor first, so no deletion changes an item that is still to be deleted.
   */
  private static Map<String, Integer> successorsInside(List<Entry> entries) {
    Map<String, Entry> byId = new HashMap<>();
    entries.forEach(e -> byId.put(e.id(), e));
    Map<String, Integer> successors = new HashMap<>();
    for (Entry entry : entries) {
      // A corrupt, cyclic chain must not loop.
      Set<String> seen = new HashSet<>(Set.of(entry.id()));
      String previous = entry.previousVersionId();
      for (int distance = 1; previous != null && byId.containsKey(previous) && seen.add(previous); distance++) {
        successors.merge(previous, distance, Math::max);
        previous = byId.get(previous).previousVersionId();
      }
    }
    return successors;
  }
  private static Map<String, Long> counts() {
    Map<String, Long> result = new LinkedHashMap<>();
    TYPES.forEach(t -> result.put(t, 0L));
    return result;
  }
}
