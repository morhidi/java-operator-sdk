package io.javaoperatorsdk.operator.processing.dependent;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.config.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.config.dependent.DependentResourceSpec;
import io.javaoperatorsdk.operator.api.reconciler.Cleaner;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ContextInitializer;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceInitializer;
import io.javaoperatorsdk.operator.api.reconciler.Ignore;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.dependent.Deleter;
import io.javaoperatorsdk.operator.api.reconciler.dependent.DependentResource;
import io.javaoperatorsdk.operator.api.reconciler.dependent.EventSourceProvider;
import io.javaoperatorsdk.operator.api.reconciler.dependent.managed.DependentResourceConfigurator;
import io.javaoperatorsdk.operator.api.reconciler.dependent.managed.KubernetesClientAware;
import io.javaoperatorsdk.operator.processing.Controller;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;

@SuppressWarnings({"rawtypes", "unchecked"})
@Ignore
public class DependentResourceManager<P extends HasMetadata>
    implements EventSourceInitializer<P>, Reconciler<P>, Cleaner<P> {

  private static final Logger log = LoggerFactory.getLogger(DependentResourceManager.class);

  private final Reconciler<P> reconciler;
  private final ControllerConfiguration<P> controllerConfiguration;
  private List<DependentResource> dependents;
  private boolean requiresCleanup;

  public DependentResourceManager(Controller<P> controller) {
    this.reconciler = controller.getReconciler();
    this.controllerConfiguration = controller.getConfiguration();
  }

  @Override
  public List<EventSource> prepareEventSources(EventSourceContext<P> context) {
    final var dependentResources = controllerConfiguration.getDependentResources();
    final var sources = new ArrayList<EventSource>(dependentResources.size());
    final boolean[] requiresCleanupHolder = new boolean[] {false};
    dependents =
        dependentResources.stream()
            .map(
                drc -> {
                  final var dependentResource = createAndConfigureFrom(drc, context.getClient());
                  if (dependentResource instanceof EventSourceProvider) {
                    EventSourceProvider provider = (EventSourceProvider) dependentResource;
                    sources.add(provider.initEventSource(context));
                  }
                  if (dependentResource instanceof Deleter) {
                    requiresCleanupHolder[0] = true;
                  }
                  return dependentResource;
                })
            .collect(Collectors.toList());
    requiresCleanup = requiresCleanupHolder[0];
    return sources;
  }

  @Override
  public UpdateControl<P> reconcile(P resource, Context<P> context) {
    initContextIfNeeded(resource, context);
    dependents.forEach(dependent -> dependent.reconcile(resource, context));
    return UpdateControl.noUpdate();
  }

  @Override
  public DeleteControl cleanup(P resource, Context<P> context) {
    initContextIfNeeded(resource, context);
    // cleanup in reverse order
    for (int i = dependents.size() - 1; i >= 0; i--) {
      var dependent = dependents.get(i);
      if (dependent instanceof Deleter) {
        ((Deleter<P>) dependent)
            .delete(resource, context);
      }
    }
    return DeleteControl.defaultDelete();
  }

  private void initContextIfNeeded(P resource, Context context) {
    if (reconciler instanceof ContextInitializer) {
      final var initializer = (ContextInitializer<P>) reconciler;
      initializer.initContext(resource, context);
    }
  }

  private DependentResource createAndConfigureFrom(DependentResourceSpec dependentResourceSpec,
      KubernetesClient client) {
    try {
      DependentResource dependentResource =
          (DependentResource) dependentResourceSpec.getDependentResourceClass()
              .getConstructor().newInstance();

      if (dependentResource instanceof KubernetesClientAware) {
        ((KubernetesClientAware) dependentResource).setKubernetesClient(client);
      }

      if (dependentResource instanceof DependentResourceConfigurator) {
        final var configurator = (DependentResourceConfigurator) dependentResource;
        dependentResourceSpec.getDependentResourceConfiguration()
            .ifPresent(configurator::configureWith);
      }
      return dependentResource;
    } catch (InstantiationException | NoSuchMethodException | IllegalAccessException
        | InvocationTargetException e) {
      throw new IllegalStateException(e);
    }
  }

  public List<DependentResource> getDependents() {
    return dependents;
  }

  public boolean requiresCleanup() {
    return requiresCleanup;
  }

}
