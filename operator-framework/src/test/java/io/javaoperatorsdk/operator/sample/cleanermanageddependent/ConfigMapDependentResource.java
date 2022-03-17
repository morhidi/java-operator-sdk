package io.javaoperatorsdk.operator.sample.cleanermanageddependent;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CrudKubernetesDependentResource;

public class ConfigMapDependentResource extends
    CrudKubernetesDependentResource<ConfigMap, CleanerForManagedDependentCustomResource> {

  private static final AtomicInteger numberOfCleanupExecutions = new AtomicInteger(0);

  @Override
  protected ConfigMap desired(CleanerForManagedDependentCustomResource primary,
      Context<CleanerForManagedDependentCustomResource> context) {

    ConfigMap configMap = new ConfigMap();
    configMap.setMetadata(new ObjectMeta());
    configMap.getMetadata().setName(primary.getMetadata().getName());
    configMap.getMetadata().setNamespace(primary.getMetadata().getNamespace());
    HashMap<String, String> data = new HashMap<>();
    data.put("key1", "val1");
    configMap.setData(data);
    return configMap;
  }

  @Override
  public void cleanup(CleanerForManagedDependentCustomResource primary,
      Context<CleanerForManagedDependentCustomResource> context) {
    super.cleanup(primary, context);
    numberOfCleanupExecutions.incrementAndGet();
  }

  public static int getNumberOfCleanupExecutions() {
    return numberOfCleanupExecutions.get();
  }
}
