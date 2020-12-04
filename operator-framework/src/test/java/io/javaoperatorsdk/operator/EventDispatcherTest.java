package io.javaoperatorsdk.operator;

import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.Watcher;
import io.javaoperatorsdk.operator.api.DeleteControl;
import io.javaoperatorsdk.operator.api.ResourceController;
import io.javaoperatorsdk.operator.api.UpdateControl;
import io.javaoperatorsdk.operator.processing.EventDispatcher;
import io.javaoperatorsdk.operator.processing.ExecutionScope;
import io.javaoperatorsdk.operator.processing.event.Event;
import io.javaoperatorsdk.operator.processing.event.internal.CustomResourceEvent;
import io.javaoperatorsdk.operator.processing.event.internal.TimerEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static io.javaoperatorsdk.operator.processing.KubernetesResourceUtils.getUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class EventDispatcherTest {

    private static final String DEFAULT_FINALIZER = "finalizer";
    private CustomResource testCustomResource;
    private EventDispatcher eventDispatcher;
    private ResourceController<CustomResource> controller = mock(ResourceController.class);
    private EventDispatcher.CustomResourceFacade customResourceFacade = mock(EventDispatcher.CustomResourceFacade.class);

    @BeforeEach
    void setup() {
        eventDispatcher = new EventDispatcher(controller,
                DEFAULT_FINALIZER, customResourceFacade);

        testCustomResource = TestUtils.testCustomResource();
        testCustomResource.getMetadata().setFinalizers(new ArrayList<>(Collections.singletonList(DEFAULT_FINALIZER)));

        when(controller.createOrUpdateResource(eq(testCustomResource), any())).thenReturn(UpdateControl.updateCustomResource(testCustomResource));
        when(controller.deleteResource(eq(testCustomResource), any())).thenReturn(DeleteControl.DEFAULT_DELETE);
        when(customResourceFacade.replaceWithLock(any())).thenReturn(null);
    }

    @Test
    void callCreateOrUpdateOnNewResource() {
        eventDispatcher.handleEvent(executionScopeWithCREvent(Watcher.Action.ADDED, testCustomResource));
        verify(controller, times(1)).createOrUpdateResource(ArgumentMatchers.eq(testCustomResource), any());
    }

    @Test
    void updatesOnlyStatusSubResource() {
        when(controller.createOrUpdateResource(eq(testCustomResource), any()))
                .thenReturn(UpdateControl.updateStatusSubResource(testCustomResource));

        eventDispatcher.handleEvent(executionScopeWithCREvent(Watcher.Action.ADDED, testCustomResource));

        verify(customResourceFacade, times(1)).updateStatus(testCustomResource);
        verify(customResourceFacade, never()).replaceWithLock(any());
    }


    @Test
    void callCreateOrUpdateOnModifiedResource() {
        eventDispatcher.handleEvent(executionScopeWithCREvent(Watcher.Action.MODIFIED, testCustomResource));
        verify(controller, times(1)).createOrUpdateResource(ArgumentMatchers.eq(testCustomResource), any());
    }

    @Test
    void adsDefaultFinalizerOnCreateIfNotThere() {
        eventDispatcher.handleEvent(executionScopeWithCREvent(Watcher.Action.MODIFIED, testCustomResource));
        verify(controller, times(1))
                .createOrUpdateResource(argThat(testCustomResource ->
                        testCustomResource.getMetadata().getFinalizers().contains(DEFAULT_FINALIZER)), any());
    }

    @Test
    void callsDeleteIfObjectHasFinalizerAndMarkedForDelete() {
        testCustomResource.getMetadata().setDeletionTimestamp("2019-8-10");
        testCustomResource.getMetadata().getFinalizers().add(DEFAULT_FINALIZER);

        eventDispatcher.handleEvent(executionScopeWithCREvent(Watcher.Action.MODIFIED, testCustomResource));

        verify(controller, times(1)).deleteResource(eq(testCustomResource), any());
    }

    /**
     * Note that there could be more finalizers. Out of our control.
     */
    @Test
    void callDeleteOnControllerIfMarkedForDeletionButThereIsNoDefaultFinalizer() {
        markForDeletion(testCustomResource);

        eventDispatcher.handleEvent(executionScopeWithCREvent(Watcher.Action.MODIFIED, testCustomResource));

        verify(controller).deleteResource(eq(testCustomResource), any());
    }

    @Test
    void removesDefaultFinalizerOnDelete() {
        markForDeletion(testCustomResource);

        eventDispatcher.handleEvent(executionScopeWithCREvent(Watcher.Action.MODIFIED, testCustomResource));

        assertEquals(0, testCustomResource.getMetadata().getFinalizers().size());
        verify(customResourceFacade, times(1)).replaceWithLock(any());
    }

    @Test
    void doesNotRemovesTheFinalizerIfTheDeleteNotMethodInstructsIt() {
        when(controller.deleteResource(eq(testCustomResource), any())).thenReturn(DeleteControl.NO_FINALIZER_REMOVAL);
        markForDeletion(testCustomResource);

        eventDispatcher.handleEvent(executionScopeWithCREvent(Watcher.Action.MODIFIED, testCustomResource));

        assertEquals(1, testCustomResource.getMetadata().getFinalizers().size());
        verify(customResourceFacade, never()).replaceWithLock(any());
    }

    @Test
    void doesNotUpdateTheResourceIfNoUpdateUpdateControl() {
        when(controller.createOrUpdateResource(eq(testCustomResource), any())).thenReturn(UpdateControl.noUpdate());

        eventDispatcher.handleEvent(executionScopeWithCREvent(Watcher.Action.MODIFIED, testCustomResource));
        verify(customResourceFacade, never()).replaceWithLock(any());
        verify(customResourceFacade, never()).updateStatus(testCustomResource);
    }

    @Test
    void addsFinalizerIfNotMarkedForDeletionAndEmptyCustomResourceReturned() {
        removeFinalizers(testCustomResource);
        when(controller.createOrUpdateResource(eq(testCustomResource), any())).thenReturn(UpdateControl.noUpdate());

        eventDispatcher.handleEvent(executionScopeWithCREvent(Watcher.Action.MODIFIED, testCustomResource));

        assertEquals(1, testCustomResource.getMetadata().getFinalizers().size());
        verify(customResourceFacade, times(1)).replaceWithLock(any());
    }

    @Test
    void doesNotCallDeleteIfMarkedForDeletionButNotOurFinalizer() {
        removeFinalizers(testCustomResource);
        markForDeletion(testCustomResource);

        eventDispatcher.handleEvent(executionScopeWithCREvent(Watcher.Action.MODIFIED, testCustomResource));

        verify(customResourceFacade, never()).replaceWithLock(any());
        verify(controller, never()).deleteResource(eq(testCustomResource), any());
    }

    @Test
    void executeControllerRegardlessGenerationInNonGenerationAwareMode() {
        eventDispatcher.handleEvent(executionScopeWithCREvent(Watcher.Action.MODIFIED, testCustomResource));
        eventDispatcher.handleEvent(executionScopeWithCREvent(Watcher.Action.MODIFIED, testCustomResource));

        verify(controller, times(2)).createOrUpdateResource(eq(testCustomResource), any());
    }

    private void markForDeletion(CustomResource customResource) {
        customResource.getMetadata().setDeletionTimestamp("2019-8-10");
    }

    private void removeFinalizers(CustomResource customResource) {
        customResource.getMetadata().getFinalizers().clear();
    }

    public ExecutionScope executionScopeWithCREvent(Watcher.Action action, CustomResource resource, Event... otherEvents) {
        CustomResourceEvent event = new CustomResourceEvent(action, resource, null);
        List<Event> eventList = new ArrayList<>(1 + otherEvents.length);
        eventList.add(event);
        eventList.addAll(Arrays.asList(otherEvents));
        return new ExecutionScope(eventList, resource);
    }

}
