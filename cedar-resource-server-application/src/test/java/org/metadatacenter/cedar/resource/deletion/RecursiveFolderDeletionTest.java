package org.metadatacenter.cedar.resource.deletion;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.metadatacenter.cedar.resource.deletion.RecursiveFolderDeletion.*;

class RecursiveFolderDeletionTest {
  private static Entry entry(String id, String type, String parent, int depth) {
    return new Entry(id, id, type, parent, depth, 1, "\"1\"", true, true, false);
  }
  private static final Entry ROOT = entry("root", "folder", "home", 0);
  private static final Entry SUB = entry("sub", "folder", "root", 1);
  private static final Entry TEMPLATE = entry("template", "template", "root", 1);
  private static final Entry INSTANCE = entry("instance", "instance", "sub", 2);
  private static final class Fake implements Store {
    Inventory current = new Inventory(List.of(ROOT, SUB, TEMPLATE, INSTANCE), Map.of("template", List.of("instance")));
    List<String> removed = new ArrayList<>();
    String fail;
    public Inventory inventory() { return current; }
    public Step delete(Entry e) {
      if (e.id().equals(fail)) return new Step(false, Reason.ITEM_CHANGED);
      removed.add(e.id()); return new Step(true, Reason.COMPLETED);
    }
  }
  @Test void internalInstancesAreAllowedAndDeletedBeforeTemplatesAndFolders() throws Exception {
    Fake store = new Fake(); var service = new RecursiveFolderDeletion(store, "user", "root");
    Plan plan = service.plan();
    assertTrue(plan.allowed()); assertEquals(1, plan.templatesWithInstances());
    assertEquals(0, plan.templatesWithOutsideInstances());
    assertEquals(Map.of("folder", 2L, "template", 1L, "element", 0L, "field", 0L, "instance", 1L), plan.counts());
    assertEquals("completed", service.execute(plan.token()).status());
    assertEquals(List.of("instance", "template", "sub", "root"), store.removed);
  }
  @Test void outsideReferencesRefuseTheWholeOperationAndCountAffectedTemplates() throws Exception {
    Fake store = new Fake(); store.current = new Inventory(store.current.entries(), Map.of("template", List.of("instance", "outside", "hidden")));
    var service = new RecursiveFolderDeletion(store, "user", "root"); Plan plan = service.plan();
    assertFalse(plan.allowed()); assertEquals(1, plan.templatesWithOutsideInstances()); assertEquals(2, plan.instancesOutside());
    assertEquals("blocked", service.execute(plan.token()).status()); assertTrue(store.removed.isEmpty());
    assertFalse(plan.items().toString().contains("outside"));
  }
  @Test void unreadableDescendantIsCountedButItsIdentityIsNotExposed() throws Exception {
    Fake store = new Fake();
    var hidden = new Entry("secret", "Private name", "field", "root", 1, 1, "\"1\"", false, false, false);
    store.current = new Inventory(List.of(ROOT, hidden), Map.of());
    var service = new RecursiveFolderDeletion(store, "user", "root"); Plan plan = service.plan();
    assertFalse(plan.allowed()); assertEquals(1, plan.restrictedItems());
    Item item = plan.items().stream().filter(i -> i.type().equals("field")).findFirst().orElseThrow();
    assertNull(item.id()); assertNull(item.name());
    assertEquals("blocked", service.execute(plan.token()).status()); assertTrue(store.removed.isEmpty());
  }
  @Test void changedTopologyOrReferencesInvalidateConfirmationEvenWhenCountsAreEqual() throws Exception {
    Fake store = new Fake(); var service = new RecursiveFolderDeletion(store, "user", "root"); String token = service.plan().token();
    store.current = new Inventory(store.current.entries(), Map.of("template", List.of("outside")));
    assertEquals("changed", service.execute(token).status()); assertTrue(store.removed.isEmpty());
    store.current = new Inventory(List.of(ROOT, SUB, TEMPLATE, entry("replacement", "instance", "sub", 2)), Map.of("template", List.of("replacement")));
    assertEquals("changed", service.execute(token).status()); assertTrue(store.removed.isEmpty());
  }
  @Test void changedPermissionAndUserInvalidateConfirmation() throws Exception {
    Fake store = new Fake(); var service = new RecursiveFolderDeletion(store, "user", "root"); String token = service.plan().token();
    assertEquals("changed", new RecursiveFolderDeletion(store, "another", "root").execute(token).status());
    var denied = new Entry(ROOT.id(), ROOT.name(), ROOT.type(), ROOT.parentId(), 0, 1, "\"1\"", true, false, false);
    store.current = new Inventory(List.of(denied, SUB, TEMPLATE, INSTANCE), store.current.references());
    assertEquals("changed", service.execute(token).status()); assertTrue(store.removed.isEmpty());
  }
  @Test void stopAfterFailureAndReportCompletedWorkWithoutDeletingParents() throws Exception {
    Fake store = new Fake(); store.fail = "template";
    var service = new RecursiveFolderDeletion(store, "user", "root"); Outcome result = service.execute(service.plan().token());
    assertEquals("stopped", result.status()); assertEquals(1, result.deleted().get("instance")); assertEquals(3, result.remaining());
    assertEquals(List.of("instance"), store.removed);
  }
  @Test void protectedDescendantRefusesTheWholeOperation() throws Exception {
    Fake store = new Fake();
    Entry protectedChild = new Entry("protected", "Protected", "folder", "root", 1, 1, "\"1\"", true, true, true);
    store.current = new Inventory(List.of(ROOT, protectedChild), Map.of());
    var service = new RecursiveFolderDeletion(store, "user", "root");
    Plan plan = service.plan();
    assertFalse(plan.allowed()); assertEquals(1, plan.protectedFolders());
    assertEquals("blocked", service.execute(plan.token()).status()); assertTrue(store.removed.isEmpty());
  }
  @Test void graphIterationOrderDoesNotChangeTheConfirmation() throws Exception {
    Fake store = new Fake(); var service = new RecursiveFolderDeletion(store, "user", "root");
    String token = service.plan().token();
    store.current = new Inventory(List.of(INSTANCE, TEMPLATE, SUB, ROOT), store.current.references());
    assertEquals(token, service.plan().token());
  }
  @Test void incompleteReferenceInventoryFailsClosed() {
    Fake store = new Fake(); store.current = new Inventory(store.current.entries(), Map.of());
    assertThrows(IllegalStateException.class, () -> new RecursiveFolderDeletion(store, "user", "root").plan());
    assertTrue(store.removed.isEmpty());
  }
  @Test void aLostDeleteResponseRequiresAReinventoryBeforeRetryingTheSurvivors() throws Exception {
    var remaining = new LinkedHashMap<String,Entry>();
    for (Entry entry : List.of(ROOT,SUB,TEMPLATE,INSTANCE)) remaining.put(entry.id(),entry);
    var attempts = new ArrayList<String>();
    Store store = new Store() {
      public Inventory inventory() {
        return new Inventory(new ArrayList<>(remaining.values()),
            Map.of("template",remaining.containsKey("instance") ? List.of("instance") : List.of()));
      }
      public Step delete(Entry entry) throws Exception {
        attempts.add(entry.id());
        assertNotNull(remaining.remove(entry.id()),"a retry must not repeat a completed delete");
        if (entry.id().equals("instance")) throw new java.io.IOException("connection lost after committing delete");
        return new Step(true,Reason.COMPLETED);
      }
    };
    var service = new RecursiveFolderDeletion(store,"user","root");
    String token = service.plan().token();
    Outcome lost = service.execute(token);
    assertEquals("stopped",lost.status());
    assertEquals(0,lost.deleted().get("instance"),"the unacknowledged delete must not be reported as confirmed");
    assertTrue(remaining.containsKey("root"));
    assertEquals("changed",service.execute(token).status());
    assertEquals(List.of("instance"),attempts);
    assertEquals("completed",service.execute(service.plan().token()).status());
    assertTrue(remaining.isEmpty());
    assertEquals(List.of("instance","template","sub","root"),attempts);
  }

}
