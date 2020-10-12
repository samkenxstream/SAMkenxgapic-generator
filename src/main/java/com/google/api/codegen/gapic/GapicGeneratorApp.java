/* Copyright 2016 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.api.codegen.gapic;

import com.google.api.codegen.ArtifactType;
import com.google.api.codegen.ConfigProto;
import com.google.api.codegen.common.CodeGenerator;
import com.google.api.codegen.common.GeneratedResult;
import com.google.api.codegen.common.TargetLanguage;
import com.google.api.codegen.config.ApiDefaultsConfig;
import com.google.api.codegen.config.DependenciesConfig;
import com.google.api.codegen.config.GapicProductConfig;
import com.google.api.codegen.config.PackageMetadataConfig;
import com.google.api.codegen.config.PackagingConfig;
import com.google.api.codegen.config.TransportProtocol;
import com.google.api.codegen.grpc.ServiceConfig;
import com.google.api.codegen.samplegen.v1p2.SampleConfigProto;
import com.google.api.codegen.util.MultiYamlReader;
import com.google.api.codegen.util.ProtoParser;
import com.google.api.codegen.util.SampleConfigSanitizer;
import com.google.api.tools.framework.model.ConfigSource;
import com.google.api.tools.framework.model.Diag;
import com.google.api.tools.framework.model.SimpleLocation;
import com.google.api.tools.framework.model.stages.Merged;
import com.google.api.tools.framework.tools.ToolDriverBase;
import com.google.api.tools.framework.tools.ToolOptions;
import com.google.api.tools.framework.tools.ToolOptions.Option;
import com.google.api.tools.framework.tools.ToolUtil;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.TypeLiteral;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Main class for the code generator. */
public class GapicGeneratorApp extends ToolDriverBase {
  public static final Option<String> LANGUAGE =
      ToolOptions.createOption(String.class, "language", "The target language.", "");
  public static final Option<String> OUTPUT_FILE =
      ToolOptions.createOption(
          String.class,
          "output_file",
          "The name of the output file or folder to put generated code.",
          "");
  public static final Option<String> PROTO_PACKAGE =
      ToolOptions.createOption(
          String.class,
          "proto_package",
          "The proto package designating the files actually intended for output.\n"
              + "This option is required if the GAPIC generator config files are not given.",
          "");
  public static final Option<String> CLIENT_PACKAGE =
      ToolOptions.createOption(
          String.class,
          "client_package",
          "The desired package name for the generated Java client. \n",
          "");

  public static final Option<List<String>> GENERATOR_CONFIG_FILES =
      ToolOptions.createOption(
          new TypeLiteral<List<String>>() {},
          "config_files",
          "The list of library configuration YAML files for the code generator.",
          ImmutableList.of());

  public static final Option<List<String>> SAMPLE_CONFIG_FILES =
      ToolOptions.createOption(
          new TypeLiteral<List<String>>() {},
          "sample_config_files",
          "The list of sample configuration YAML files for the code generator.",
          ImmutableList.of());

  public static final Option<String> PACKAGE_CONFIG2_FILE =
      ToolOptions.createOption(String.class, "package_config2", "The packaging configuration.", "");

  public static final Option<List<String>> ENABLED_ARTIFACTS =
      ToolOptions.createOption(
          new TypeLiteral<List<String>>() {},
          "enabled_artifacts",
          "The artifacts to be generated by the code generator.",
          ImmutableList.of());

  public static final Option<Boolean> DEV_SAMPLES =
      ToolOptions.createOption(
          Boolean.class,
          "dev_samples",
          "Whether to generate samples in non-production-ready languages.",
          false);

  public static final Option<String> GRPC_SERVICE_CONFIG =
      ToolOptions.createOption(
          String.class,
          "grpc_service_config",
          "The filepath of the JSON gRPC Service Config file.",
          "");

  public static final Option<String> TRANSPORT =
      ToolOptions.createOption(
          String.class,
          "transport",
          "List of transports to use ('rest' or 'grpc') separated by '+'. NOTE: For now"
              + " we only support the first transport in the list.",
          "grpc");

  private ArtifactType artifactType;

  private final GapicWriter gapicWriter;

  /**
   * Constructs a code generator api based on given options.
   *
   * @param gapicWriter : The object that will write out the generator output.
   */
  public GapicGeneratorApp(
      ToolOptions options, ArtifactType artifactType, GapicWriter gapicWriter) {
    super(options);
    this.artifactType = artifactType;
    this.gapicWriter = gapicWriter;
  }

  @Override
  public ExtensionRegistry getPlatformExtensions() {
    ExtensionRegistry extensionRegistry = super.getPlatformExtensions();
    ProtoParser.registerAllExtensions(extensionRegistry);
    return extensionRegistry;
  }

  @Override
  protected void process() throws Exception {

    String protoPackage = Strings.emptyToNull(options.get(PROTO_PACKAGE));

    // Read the GAPIC config, if it was given, and convert it to proto.
    List<String> configFileNames = options.get(GENERATOR_CONFIG_FILES);
    ConfigProto configProto = null;
    if (configFileNames.size() > 0) {
      // Read the YAML config and convert it to proto.
      ConfigSource configSource =
          loadConfigFromFiles(
              configFileNames,
              ConfigProto.getDescriptor().getFullName(),
              ConfigProto.getDefaultInstance());
      if (configSource == null) {
        return;
      }

      configProto = (ConfigProto) configSource.getConfig();
      if (configProto == null) {
        return;
      }
    }

    // Consume gRPC Service Config if it is given with gapic_v2.
    String gRPCServiceConfigPath = options.get(GRPC_SERVICE_CONFIG);
    ServiceConfig gRPCServiceConfig = null;
    if (!Strings.isNullOrEmpty(gRPCServiceConfigPath)
        && configProto.getConfigSchemaVersion().equals("2.0.0")) {
      ServiceConfig.Builder builder = ServiceConfig.newBuilder();
      FileReader file = new FileReader(gRPCServiceConfigPath);
      JsonFormat.parser().merge(file, builder);

      gRPCServiceConfig = builder.build();
    }

    // Read the sample configs, if they are given, and convert them to protos.
    SampleConfigProto sampleConfigProto = null;
    List<String> sampleConfigFileNames = options.get(SAMPLE_CONFIG_FILES);
    if (sampleConfigFileNames.size() > 0) {
      ConfigSource configSource =
          loadConfigFromFiles(
              SampleConfigSanitizer.sanitize(sampleConfigFileNames),
              SampleConfigProto.getDescriptor().getFullName(),
              SampleConfigProto.getDefaultInstance());

      // TODO(hzyi): Verify this works for repeated fields as well
      // TODO(hzyi): Allow users to put arbitrary top-level directives not
      // used by gapic-generator
      sampleConfigProto = (SampleConfigProto) configSource.getConfig();
    }

    model.establishStage(Merged.KEY);

    if (model.getDiagReporter().getDiagCollector().getErrorCount() > 0) {
      for (Diag diag : model.getDiagReporter().getDiagCollector().getDiags()) {
        System.err.println(diag.toString());
      }
      return;
    }

    ApiDefaultsConfig apiDefaultsConfig = ApiDefaultsConfig.load();
    DependenciesConfig dependenciesConfig = DependenciesConfig.load();

    TargetLanguage language;
    if (!Strings.isNullOrEmpty(options.get(LANGUAGE))) {
      language = TargetLanguage.fromString(options.get(LANGUAGE).toUpperCase());
    } else {
      throw new IllegalArgumentException("Language not set by --language option.");
    }

    String clientPackage = Strings.emptyToNull(options.get(CLIENT_PACKAGE));
    String transport = options.get(TRANSPORT).toLowerCase();

    TransportProtocol tp;
    if (transport.equals("grpc")) {
      tp = TransportProtocol.GRPC;
    } else if (transport.equals("rest")) {
      tp = TransportProtocol.HTTP;
    } else {
      throw new IllegalArgumentException("Unknown transport protocol: " + transport);
    }

    GapicProductConfig productConfig =
        GapicProductConfig.create(
            model,
            configProto,
            sampleConfigProto,
            protoPackage,
            clientPackage,
            language,
            gRPCServiceConfig,
            tp);
    if (productConfig == null) {
      ToolUtil.reportDiags(model.getDiagReporter().getDiagCollector(), true);
      return;
    }

    PackagingConfig packagingConfig;
    if (!Strings.isNullOrEmpty(options.get(PACKAGE_CONFIG2_FILE))) {
      packagingConfig = PackagingConfig.load(options.get(PACKAGE_CONFIG2_FILE));
    } else {
      packagingConfig =
          PackagingConfig.loadFromProductConfig(productConfig.getInterfaceConfigMap());
    }

    PackageMetadataConfig packageConfig =
        PackageMetadataConfig.createFromPackaging(
            apiDefaultsConfig, dependenciesConfig, packagingConfig);

    // TODO(hzyi-google): Once we switch to sample configs, require an
    // additional check to generate samples:
    // `sampleConfigProto != null`
    ArtifactFlags artifactFlags =
        new ArtifactFlags(options.get(ENABLED_ARTIFACTS), artifactType, options.get(DEV_SAMPLES));
    List<CodeGenerator<?>> generators =
        GapicGeneratorFactory.create(language, model, productConfig, packageConfig, artifactFlags);
    ImmutableMap.Builder<String, GeneratedResult<?>> generatedResults = ImmutableMap.builder();
    for (CodeGenerator<?> generator : generators) {
      Map<String, ? extends GeneratedResult<?>> generatorResult = generator.generate();
      for (Map.Entry<String, ? extends GeneratedResult<?>> entry : generatorResult.entrySet()) {
        generatedResults.put(entry.getKey(), entry.getValue());
      }
    }

    gapicWriter.writeCodeGenOutput(
        generatedResults.build(), model.getDiagReporter().getDiagCollector());
  }

  private ConfigSource loadConfigFromFiles(
      List<String> configFileNames, String configClassName, Message defaultConfigInstance) {
    List<File> configFiles = pathsToFiles(configFileNames);
    if (model.getDiagReporter().getDiagCollector().getErrorCount() > 0) {
      return null;
    }
    ImmutableMap<String, Message> supportedConfigTypes =
        ImmutableMap.of(configClassName, defaultConfigInstance);
    return MultiYamlReader.read(
        model.getDiagReporter().getDiagCollector(), configFiles, supportedConfigTypes);
  }

  private List<File> pathsToFiles(List<String> configFileNames) {
    List<File> files = new ArrayList<>();

    for (String configFileName : configFileNames) {
      File file = model.findDataFile(configFileName);
      if (file == null) {
        error("Cannot find configuration file '%s'.", configFileName);
        continue;
      }
      files.add(file);
    }

    return files;
  }

  private void error(String message, Object... args) {
    model
        .getDiagReporter()
        .getDiagCollector()
        .addDiag(Diag.error(SimpleLocation.TOPLEVEL, message, args));
  }
}
