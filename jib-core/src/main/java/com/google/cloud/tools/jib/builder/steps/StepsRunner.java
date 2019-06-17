/*
 * Copyright 2018 Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.builder.steps;

import com.google.cloud.tools.jib.api.Credential;
import com.google.cloud.tools.jib.api.RegistryException;
import com.google.cloud.tools.jib.blob.BlobDescriptor;
import com.google.cloud.tools.jib.builder.ProgressEventDispatcher;
import com.google.cloud.tools.jib.builder.steps.PullBaseImageStep.ImageAndAuthorization;
import com.google.cloud.tools.jib.configuration.BuildConfiguration;
import com.google.cloud.tools.jib.docker.DockerClient;
import com.google.cloud.tools.jib.global.JibSystemProperties;
import com.google.cloud.tools.jib.http.Authorization;
import com.google.cloud.tools.jib.image.Image;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Runs steps for building an image.
 *
 * <p>Use by first calling {@link #begin} and then calling the individual step running methods. Note
 * that order matters, so make sure that steps are run before other steps that depend on them. Wait
 * on the last step by calling the respective {@code wait...} methods.
 */
public class StepsRunner {

  /** Holds the individual step results. */
  private static class StepResults {

    private static <E> ListenableFuture<E> failedFuture() {
      return Futures.immediateFailedFuture(
          new IllegalStateException("invalid usage; required step not configured"));
    }

    private Future<ImageAndAuthorization> baseImageAndAuth = failedFuture();
    private Future<List<ListenableFuture<CachedLayerAndName>>> baseImageLayers = failedFuture();
    @Nullable private List<ListenableFuture<CachedLayerAndName>> applicationLayers;
    private Future<Image> builtImage = failedFuture();

    private Future<Credential> targetRegistryCredentials = failedFuture();
    private ListenableFuture<Authorization> pushAuthorization = failedFuture();
    private Future<List<Future<BlobDescriptor>>> baseImageLayerPushResults = failedFuture();
    @Nullable private List<Future<BlobDescriptor>> applicationLayerPushResults;
    private Future<BlobDescriptor> containerConfigurationPushResult = failedFuture();
    private Future<BuildResult> buildResult = failedFuture();
  }

  /**
   * Starts building the steps to run.
   *
   * @param buildConfiguration the {@link BuildConfiguration}
   * @return a new {@link StepsRunner}
   */
  public static StepsRunner begin(BuildConfiguration buildConfiguration) {
    ExecutorService executorService =
        JibSystemProperties.isSerializedExecutionEnabled()
            ? MoreExecutors.newDirectExecutorService()
            : buildConfiguration.getExecutorService();

    return new StepsRunner(MoreExecutors.listeningDecorator(executorService), buildConfiguration);
  }

  private static <E> List<E> realizeFutures(List<? extends Future<E>> futures)
      throws InterruptedException, ExecutionException {
    List<E> values = new ArrayList<>();
    for (Future<E> future : futures) {
      values.add(future.get());
    }
    return values;
  }

  private final StepResults results = new StepResults();

  private final ListeningExecutorService executorService;
  private final BuildConfiguration buildConfiguration;

  // We save steps to run by wrapping each step into a Runnable, only because of the unfortunate
  // chicken-and-egg situation arising from using ProgressEventDispatcher. The current
  // ProgressEventDispatcher model requires knowing in advance how many units of work (i.e., steps)
  // we should perform. That is, to instantiate a root ProgressEventDispatcher instance, we should
  // know ahead how many steps we will run. However, to instantiate a step, we need a root progress
  // dispatcher. So, we wrap steps into Runnables and save them to run them later. Then we can count
  // the number of Runnables and, create a root dispatcher, and run the saved Runnables.
  private final List<Runnable> stepsToRun = new ArrayList<>();

  @Nullable private String rootProgressDescription;
  private Supplier<ProgressEventDispatcher.Factory> childProgressDispatcherFactorySupplier =
      () -> {
        throw new IllegalStateException("root progress dispatcher uninstantiated");
      };

  private StepsRunner(
      ListeningExecutorService executorService, BuildConfiguration buildConfiguration) {
    this.executorService = executorService;
    this.buildConfiguration = buildConfiguration;
  }

  public StepsRunner dockerLoadSteps(DockerClient dockerClient) {
    rootProgressDescription = "building image to Docker daemon";
    // build and cache
    stepsToRun.add(this::pullBaseImage);
    stepsToRun.add(this::pullAndCacheBaseImageLayers);
    stepsToRun.add(this::buildAndCacheApplicationLayers);
    stepsToRun.add(this::buildImage);
    // load to Docker
    stepsToRun.add(() -> loadDocker(dockerClient));
    return this;
  }

  public StepsRunner tarBuildSteps(Path outputPath) {
    rootProgressDescription = "building image to tar file";
    // build and cache
    stepsToRun.add(this::pullBaseImage);
    stepsToRun.add(this::pullAndCacheBaseImageLayers);
    stepsToRun.add(this::buildAndCacheApplicationLayers);
    stepsToRun.add(this::buildImage);
    // create a tar
    stepsToRun.add(() -> writeTarFile(outputPath));
    return this;
  }

  public StepsRunner registryPushSteps() {
    rootProgressDescription = "building image to registry";
    // build and cache
    stepsToRun.add(this::pullBaseImage);
    stepsToRun.add(this::pullAndCacheBaseImageLayers);
    stepsToRun.add(this::buildAndCacheApplicationLayers);
    stepsToRun.add(this::buildImage);
    // push to registry
    stepsToRun.add(this::retrieveTargetRegistryCredentials);
    stepsToRun.add(this::authenticatePush);
    stepsToRun.add(this::pushBaseImageLayers);
    stepsToRun.add(this::pushApplicationLayers);
    stepsToRun.add(this::pushContainerConfiguration);
    stepsToRun.add(this::pushImages);
    return this;
  }

  public BuildResult run() throws ExecutionException, InterruptedException {
    Preconditions.checkNotNull(rootProgressDescription);

    try (ProgressEventDispatcher progressEventDispatcher =
        ProgressEventDispatcher.newRoot(
            buildConfiguration.getEventHandlers(), rootProgressDescription, stepsToRun.size())) {
      childProgressDispatcherFactorySupplier = progressEventDispatcher::newChildProducer;
      stepsToRun.forEach(Runnable::run);
      return results.buildResult.get();

    } catch (ExecutionException ex) {
      ExecutionException unrolled = ex;
      while (unrolled.getCause() instanceof ExecutionException) {
        unrolled = (ExecutionException) unrolled.getCause();
      }
      throw unrolled;
    }
  }

  private void retrieveTargetRegistryCredentials() {
    results.targetRegistryCredentials =
        executorService.submit(
            RetrieveRegistryCredentialsStep.forTargetImage(
                buildConfiguration, childProgressDispatcherFactorySupplier.get()));
  }

  private void authenticatePush() {
    results.pushAuthorization =
        executorService.submit(
            () ->
                new AuthenticatePushStep(
                        buildConfiguration,
                        childProgressDispatcherFactorySupplier.get(),
                        results.targetRegistryCredentials.get())
                    .call());
  }

  private void pullBaseImage() {
    results.baseImageAndAuth =
        executorService.submit(
            new PullBaseImageStep(
                buildConfiguration, childProgressDispatcherFactorySupplier.get()));
  }

  private void pullAndCacheBaseImageLayers() {
    results.baseImageLayers =
        executorService.submit(
            () ->
                scheduleCallables(
                    PullAndCacheBaseImageLayerStep.makeList(
                        buildConfiguration,
                        childProgressDispatcherFactorySupplier.get(),
                        results.baseImageAndAuth.get())));
  }

  private void buildAndCacheApplicationLayers() {
    results.applicationLayers =
        scheduleCallables(
            BuildAndCacheApplicationLayerStep.makeList(
                buildConfiguration, childProgressDispatcherFactorySupplier.get()));
  }

  private void buildImage() {
    results.builtImage =
        executorService.submit(
            () ->
                new BuildImageStep(
                        buildConfiguration,
                        childProgressDispatcherFactorySupplier.get(),
                        results.baseImageAndAuth.get().getImage(),
                        realizeFutures(results.baseImageLayers.get()),
                        realizeFutures(Verify.verifyNotNull(results.applicationLayers)))
                    .call());
  }

  private void pushContainerConfiguration() {
    results.containerConfigurationPushResult =
        executorService.submit(
            () ->
                new PushContainerConfigurationStep(
                        buildConfiguration,
                        childProgressDispatcherFactorySupplier.get(),
                        results.pushAuthorization.get(),
                        results.builtImage.get())
                    .call());
  }

  private void pushBaseImageLayers() {
    results.baseImageLayerPushResults =
        executorService.submit(() -> pushLayers(results.baseImageLayers.get()));
  }

  private void pushApplicationLayers() {
    results.applicationLayerPushResults =
        pushLayers(Verify.verifyNotNull(results.applicationLayers));
  }

  // Special optimization for pushing layers: uses whenAllSucceed to avoid idle threads.
  private List<Future<BlobDescriptor>> pushLayers(
      List<ListenableFuture<CachedLayerAndName>> layers) {
    String stepDescription = "preparing layer pushers";
    try (ProgressEventDispatcher progressDispatcher =
        childProgressDispatcherFactorySupplier.get().create(stepDescription, layers.size())) {

      return layers
          .stream()
          .map(
              layer -> {
                ProgressEventDispatcher.Factory pusherProgressDispatcherFactory =
                    progressDispatcher.newChildProducer();
                return Futures.whenAllSucceed(layer, results.pushAuthorization)
                    .call(
                        () -> runPushLayerStep(layer, pusherProgressDispatcherFactory),
                        executorService);
              })
          .collect(Collectors.toList());
    }
  }

  private BlobDescriptor runPushLayerStep(
      Future<CachedLayerAndName> layer, ProgressEventDispatcher.Factory progressDispatcherFactory)
      throws InterruptedException, ExecutionException, IOException, RegistryException {
    return new PushLayerStep(
            buildConfiguration,
            progressDispatcherFactory,
            results.pushAuthorization.get(),
            layer.get().getCachedLayer())
        .call();
  }

  private void pushImages() {
    results.buildResult =
        executorService.submit(
            () -> {
              realizeFutures(results.baseImageLayerPushResults.get());
              realizeFutures(Verify.verifyNotNull(results.applicationLayerPushResults));

              List<ListenableFuture<BuildResult>> tagPushResults =
                  scheduleCallables(
                      PushManifestStep.makeList(
                          buildConfiguration,
                          childProgressDispatcherFactorySupplier.get(),
                          results.pushAuthorization.get(),
                          results.containerConfigurationPushResult.get(),
                          results.builtImage.get()));
              realizeFutures(tagPushResults);
              // Image (tag, or actually manifest) pushers return the same BuildResult.
              return tagPushResults.get(0).get();
            });
  }

  private void loadDocker(DockerClient dockerClient) {
    results.buildResult =
        executorService.submit(
            () ->
                new LoadDockerStep(
                        buildConfiguration,
                        childProgressDispatcherFactorySupplier.get(),
                        dockerClient,
                        results.builtImage.get())
                    .call());
  }

  private void writeTarFile(Path outputPath) {
    results.buildResult =
        executorService.submit(
            () ->
                new WriteTarFileStep(
                        buildConfiguration,
                        childProgressDispatcherFactorySupplier.get(),
                        outputPath,
                        results.builtImage.get())
                    .call());
  }

  private <E> List<ListenableFuture<E>> scheduleCallables(
      ImmutableList<? extends Callable<E>> callables) {
    return callables.stream().map(executorService::submit).collect(Collectors.toList());
  }
}
