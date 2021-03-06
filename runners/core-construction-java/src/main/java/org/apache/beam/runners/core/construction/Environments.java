/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.core.construction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import org.apache.beam.model.pipeline.v1.Endpoints.ApiServiceDescriptor;
import org.apache.beam.model.pipeline.v1.RunnerApi.CombinePayload;
import org.apache.beam.model.pipeline.v1.RunnerApi.Components;
import org.apache.beam.model.pipeline.v1.RunnerApi.DockerPayload;
import org.apache.beam.model.pipeline.v1.RunnerApi.Environment;
import org.apache.beam.model.pipeline.v1.RunnerApi.ExternalPayload;
import org.apache.beam.model.pipeline.v1.RunnerApi.PTransform;
import org.apache.beam.model.pipeline.v1.RunnerApi.ParDoPayload;
import org.apache.beam.model.pipeline.v1.RunnerApi.ProcessPayload;
import org.apache.beam.model.pipeline.v1.RunnerApi.ReadPayload;
import org.apache.beam.model.pipeline.v1.RunnerApi.StandardEnvironments;
import org.apache.beam.model.pipeline.v1.RunnerApi.WindowIntoPayload;
import org.apache.beam.sdk.util.common.ReflectHelpers;
import org.apache.beam.vendor.grpc.v1p13p1.com.google.protobuf.ByteString;
import org.apache.beam.vendor.grpc.v1p13p1.com.google.protobuf.InvalidProtocolBufferException;

/** Utilities for interacting with portability {@link Environment environments}. */
public class Environments {
  private static final ImmutableMap<String, EnvironmentIdExtractor> KNOWN_URN_SPEC_EXTRACTORS =
      ImmutableMap.<String, EnvironmentIdExtractor>builder()
          .put(PTransformTranslation.COMBINE_PER_KEY_TRANSFORM_URN, Environments::combineExtractor)
          .put(
              PTransformTranslation.COMBINE_PER_KEY_PRECOMBINE_TRANSFORM_URN,
              Environments::combineExtractor)
          .put(
              PTransformTranslation.COMBINE_PER_KEY_MERGE_ACCUMULATORS_TRANSFORM_URN,
              Environments::combineExtractor)
          .put(
              PTransformTranslation.COMBINE_PER_KEY_EXTRACT_OUTPUTS_TRANSFORM_URN,
              Environments::combineExtractor)
          .put(PTransformTranslation.PAR_DO_TRANSFORM_URN, Environments::parDoExtractor)
          .put(PTransformTranslation.SPLITTABLE_PROCESS_ELEMENTS_URN, Environments::parDoExtractor)
          .put(PTransformTranslation.READ_TRANSFORM_URN, Environments::readExtractor)
          .put(PTransformTranslation.ASSIGN_WINDOWS_TRANSFORM_URN, Environments::windowExtractor)
          .build();

  private static final EnvironmentIdExtractor DEFAULT_SPEC_EXTRACTOR = transform -> null;

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModules(ObjectMapper.findModules(ReflectHelpers.findClassLoader()));
  public static final String ENVIRONMENT_DOCKER = "DOCKER";
  public static final String ENVIRONMENT_PROCESS = "PROCESS";
  public static final String ENVIRONMENT_EXTERNAL = "EXTERNAL";
  public static final String ENVIRONMENT_EMBEDDED = "EMBEDDED"; // Non Public urn for testing
  public static final String ENVIRONMENT_LOOPBACK = "LOOPBACK"; // Non Public urn for testing

  /* For development, use the container build by the current user to ensure that the SDK harness and
   * the SDK agree on how they should interact. This should be changed to a version-specific
   * container during a release.
   *
   * See https://beam.apache.org/contribute/docker-images/ for more information on how to build a
   * container.
   */
  private static final String JAVA_SDK_HARNESS_CONTAINER_URL =
      String.format("%s-docker-apache.bintray.io/beam/java", System.getenv("USER"));
  public static final Environment JAVA_SDK_HARNESS_ENVIRONMENT =
      createDockerEnvironment(JAVA_SDK_HARNESS_CONTAINER_URL);

  private Environments() {}

  public static Environment createOrGetDefaultEnvironment(String type, String config) {
    if (Strings.isNullOrEmpty(type)) {
      return JAVA_SDK_HARNESS_ENVIRONMENT;
    }

    switch (type) {
      case ENVIRONMENT_EMBEDDED:
        return createEmbeddedEnvironment(config);
      case ENVIRONMENT_EXTERNAL:
      case ENVIRONMENT_LOOPBACK:
        return createExternalEnvironment(config);
      case ENVIRONMENT_PROCESS:
        return createProcessEnvironment(config);
      case ENVIRONMENT_DOCKER:
      default:
        return createDockerEnvironment(config);
    }
  }

  public static Environment createDockerEnvironment(String dockerImageUrl) {
    return Environment.newBuilder()
        .setUrl(dockerImageUrl)
        .setUrn(BeamUrns.getUrn(StandardEnvironments.Environments.DOCKER))
        .setPayload(
            DockerPayload.newBuilder().setContainerImage(dockerImageUrl).build().toByteString())
        .build();
  }

  private static Environment createExternalEnvironment(String config) {
    return Environment.newBuilder()
        .setUrn(BeamUrns.getUrn(StandardEnvironments.Environments.EXTERNAL))
        .setPayload(
            ExternalPayload.newBuilder()
                .setEndpoint(ApiServiceDescriptor.newBuilder().setUrl(config).build())
                .build()
                .toByteString())
        .build();
  }

  private static Environment createProcessEnvironment(String config) {
    try {
      ProcessPayloadReferenceJSON payloadReferenceJSON =
          MAPPER.readValue(config, ProcessPayloadReferenceJSON.class);
      return createProcessEnvironment(
          payloadReferenceJSON.getOs(),
          payloadReferenceJSON.getArch(),
          payloadReferenceJSON.getCommand(),
          payloadReferenceJSON.getEnv());
    } catch (IOException e) {
      throw new RuntimeException(
          String.format("Unable to parse process environment config: %s", config), e);
    }
  }

  private static Environment createEmbeddedEnvironment(String config) {
    return Environment.newBuilder()
        .setUrn(ENVIRONMENT_EMBEDDED)
        .setPayload(ByteString.copyFromUtf8(MoreObjects.firstNonNull(config, "")))
        .build();
  }

  public static Environment createProcessEnvironment(
      String os, String arch, String command, Map<String, String> env) {
    ProcessPayload.Builder builder = ProcessPayload.newBuilder();
    if (!Strings.isNullOrEmpty(os)) {
      builder.setOs(os);
    }
    if (!Strings.isNullOrEmpty(arch)) {
      builder.setArch(arch);
    }
    if (!Strings.isNullOrEmpty(command)) {
      builder.setCommand(command);
    }
    if (env != null) {
      builder.putAllEnv(env);
    }
    return Environment.newBuilder()
        .setUrn(BeamUrns.getUrn(StandardEnvironments.Environments.PROCESS))
        .setPayload(builder.build().toByteString())
        .build();
  }

  public static Optional<Environment> getEnvironment(String ptransformId, Components components) {
    try {
      PTransform ptransform = components.getTransformsOrThrow(ptransformId);
      String envId =
          KNOWN_URN_SPEC_EXTRACTORS
              .getOrDefault(ptransform.getSpec().getUrn(), DEFAULT_SPEC_EXTRACTOR)
              .getEnvironmentId(ptransform);
      if (Strings.isNullOrEmpty(envId)) {
        // Some PTransform payloads may have an unspecified (empty) Environment ID, for example a
        // WindowIntoPayload with a known WindowFn. Others will never have an Environment ID, such
        // as a GroupByKeyPayload, and the Default extractor returns null in this case.
        return Optional.empty();
      } else {
        return Optional.of(components.getEnvironmentsOrThrow(envId));
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static Optional<Environment> getEnvironment(
      PTransform ptransform, RehydratedComponents components) {
    try {
      String envId =
          KNOWN_URN_SPEC_EXTRACTORS
              .getOrDefault(ptransform.getSpec().getUrn(), DEFAULT_SPEC_EXTRACTOR)
              .getEnvironmentId(ptransform);
      if (!Strings.isNullOrEmpty(envId)) {
        // Some PTransform payloads may have an empty (default) Environment ID, for example a
        // WindowIntoPayload with a known WindowFn. Others will never have an Environment ID, such
        // as a GroupByKeyPayload, and the Default extractor returns null in this case.
        return Optional.of(components.getEnvironment(envId));
      } else {
        return Optional.empty();
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private interface EnvironmentIdExtractor {
    @Nullable
    String getEnvironmentId(PTransform transform) throws IOException;
  }

  private static String parDoExtractor(PTransform pTransform)
      throws InvalidProtocolBufferException {
    return ParDoPayload.parseFrom(pTransform.getSpec().getPayload()).getDoFn().getEnvironmentId();
  }

  private static String combineExtractor(PTransform pTransform)
      throws InvalidProtocolBufferException {
    return CombinePayload.parseFrom(pTransform.getSpec().getPayload())
        .getCombineFn()
        .getEnvironmentId();
  }

  private static String readExtractor(PTransform transform) throws InvalidProtocolBufferException {
    return ReadPayload.parseFrom(transform.getSpec().getPayload()).getSource().getEnvironmentId();
  }

  private static String windowExtractor(PTransform transform)
      throws InvalidProtocolBufferException {
    return WindowIntoPayload.parseFrom(transform.getSpec().getPayload())
        .getWindowFn()
        .getEnvironmentId();
  }

  private static class ProcessPayloadReferenceJSON {
    @Nullable private String os;
    @Nullable private String arch;
    @Nullable private String command;
    @Nullable private Map<String, String> env;

    @Nullable
    public String getOs() {
      return os;
    }

    @Nullable
    public String getArch() {
      return arch;
    }

    @Nullable
    public String getCommand() {
      return command;
    }

    @Nullable
    public Map<String, String> getEnv() {
      return env;
    }
  }
}
