// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.runtime;

import static com.google.devtools.build.lib.analysis.config.CoreOptionConverters.BUILD_SETTING_CONVERTERS;
import static com.google.devtools.build.lib.packages.RuleClass.Builder.SKYLARK_BUILD_SETTING_DEFAULT_ATTR_NAME;
import static com.google.devtools.build.lib.syntax.Type.BOOLEAN;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.packages.BuildSetting;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.pkgcache.LoadingOptions;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.TargetPatternPhaseValue;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * An options parser for starlark defined options. Takes a mutable {@link OptionsParser} that has
 * already parsed all native options (including those needed for loading). This class is in charge
 * of parsing and setting the starlark options for this {@link OptionsParser}.
 */
// TODO(juliexxia): confront the spectre of aliased build settings
public class StarlarkOptionsParser {

  private final SkyframeExecutor skyframeExecutor;
  private final PathFragment relativeWorkingDirectory;
  private final Reporter reporter;
  private final OptionsParser nativeOptionsParser;

  private StarlarkOptionsParser(
      SkyframeExecutor skyframeExecutor,
      PathFragment relativeWorkingDirectory,
      Reporter reporter,
      OptionsParser nativeOptionsParser) {
    this.skyframeExecutor = skyframeExecutor;
    this.relativeWorkingDirectory = relativeWorkingDirectory;
    this.reporter = reporter;
    this.nativeOptionsParser = nativeOptionsParser;
  }

  static StarlarkOptionsParser newStarlarkOptionsParser(
      CommandEnvironment env, OptionsParser optionsParser) {
    return new StarlarkOptionsParser(
        env.getSkyframeExecutor(),
        env.getRelativeWorkingDirectory(),
        env.getReporter(),
        optionsParser);
  }

  // TODO(juliexxia): This method somewhat reinvents the wheel of
  // OptionsParserImpl.identifyOptionAndPossibleArgument. Consider combining. This would probably
  // require multiple rounds of parsing to fit starlark-defined options into native option format.
  @VisibleForTesting
  public void parse(Command command, ExtendedEventHandler eventHandler)
      throws OptionsParsingException {
    ImmutableList.Builder<String> residue = new ImmutableList.Builder<>();
    // Map of <option name (label), <unparsed option value, loaded option>>.
    Map<String, Pair<String, Target>> unparsedOptions =
        Maps.newHashMapWithExpectedSize(nativeOptionsParser.getResidue().size());

    // sort the old residue into starlark flags and legitimate residue
    Iterator<String> unparsedArgs = nativeOptionsParser.getPreDoubleDashResidue().iterator();
    while (unparsedArgs.hasNext()) {
      String arg = unparsedArgs.next();

      // TODO(bazel-team): support single dash options?
      if (!arg.startsWith("--")) {
        residue.add(arg);
        continue;
      }

      parseArg(arg, unparsedArgs, unparsedOptions, command, eventHandler);
    }
    residue.addAll(nativeOptionsParser.getPostDoubleDashResidue());
    nativeOptionsParser.setResidue(residue.build());

    if (unparsedOptions.isEmpty()) {
      return;
    }

    ImmutableMap.Builder<String, Object> parsedOptions = new ImmutableMap.Builder<>();
    for (Map.Entry<String, Pair<String, Target>> option : unparsedOptions.entrySet()) {
      String loadedFlag = option.getKey();
      String unparsedValue = option.getValue().first;
      Target buildSettingTarget = option.getValue().second;
      BuildSetting buildSetting =
          buildSettingTarget.getAssociatedRule().getRuleClassObject().getBuildSetting();
      // Do not recognize internal options, which are treated as if they did not exist.
      if (!buildSetting.isFlag()) {
        throw new OptionsParsingException(
            String.format("Unrecognized option: %s=%s", loadedFlag, unparsedValue));
      }
      Type<?> type = buildSetting.getType();
      Converter<?> converter = BUILD_SETTING_CONVERTERS.get(type);
      Object value;
      try {
        value = converter.convert(unparsedValue);
      } catch (OptionsParsingException e) {
        throw new OptionsParsingException(
            String.format(
                "While parsing option %s=%s: '%s' is not a %s",
                loadedFlag, unparsedValue, unparsedValue, type),
            e);
      }
      if (!value.equals(
          buildSettingTarget
              .getAssociatedRule()
              .getAttributeContainer()
              .getAttr(SKYLARK_BUILD_SETTING_DEFAULT_ATTR_NAME))) {
        parsedOptions.put(loadedFlag, value);
      }
    }
    nativeOptionsParser.setStarlarkOptions(parsedOptions.build());
  }

  private void parseArg(
      String arg,
      Iterator<String> unparsedArgs,
      Map<String, Pair<String, Target>> unparsedOptions,
      Command command,
      ExtendedEventHandler eventHandler)
      throws OptionsParsingException {
    int equalsAt = arg.indexOf('=');
    String name = equalsAt == -1 ? arg.substring(2) : arg.substring(2, equalsAt);
    if (name.trim().isEmpty()) {
      throw new OptionsParsingException("Invalid options syntax: " + arg, arg);
    }
    String value = equalsAt == -1 ? null : arg.substring(equalsAt + 1);

    if (value != null) {
      // --flag=value or -flag=value form
      Target buildSettingTarget =
          loadBuildSetting(name, nativeOptionsParser, command, eventHandler);
      unparsedOptions.put(name, new Pair<>(value, buildSettingTarget));
    } else {
      boolean booleanValue = true;
      // check --noflag form
      if (name.startsWith("no")) {
        booleanValue = false;
        name = name.substring(2);
      }
      Target buildSettingTarget =
          loadBuildSetting(name, nativeOptionsParser, command, eventHandler);
      BuildSetting current =
          buildSettingTarget.getAssociatedRule().getRuleClassObject().getBuildSetting();
      if (current.getType().equals(BOOLEAN)) {
        // --boolean_flag or --noboolean_flag
        unparsedOptions.put(name, new Pair<>(String.valueOf(booleanValue), buildSettingTarget));
      } else {
        if (!booleanValue) {
          // --no(non_boolean_flag)
          throw new OptionsParsingException(
              "Illegal use of 'no' prefix on non-boolean option: " + name, name);
        }
        if (unparsedArgs.hasNext()) {
          // --flag value
          unparsedOptions.put(name, new Pair<>(unparsedArgs.next(), buildSettingTarget));
        } else {
          throw new OptionsParsingException("Expected value after " + arg);
        }
      }
    }
  }

  private Target loadBuildSetting(
      String targetToBuild,
      OptionsParser optionsParser,
      Command command,
      ExtendedEventHandler eventHandler)
      throws OptionsParsingException {
    Target buildSetting;
    try {
      TargetPatternPhaseValue result =
          skyframeExecutor.loadTargetPatterns(
              reporter,
              Collections.singletonList(targetToBuild),
              relativeWorkingDirectory,
              optionsParser.getOptions(LoadingOptions.class),
              SkyframeExecutor.DEFAULT_THREAD_COUNT,
              optionsParser.getOptions(KeepGoingOption.class).keepGoing,
              command.name().equals("test"));
      buildSetting =
          Iterables.getOnlyElement(
              result.getTargets(eventHandler, skyframeExecutor.getPackageManager()));
    } catch (InterruptedException | TargetParsingException e) {
      Thread.currentThread().interrupt();
      throw new OptionsParsingException(
          "Error loading option " + targetToBuild + ": " + e.getMessage(), e);
    }
    Rule associatedRule = buildSetting.getAssociatedRule();
    if (associatedRule == null || associatedRule.getRuleClassObject().getBuildSetting() == null) {
      throw new OptionsParsingException("Unrecognized option: " + targetToBuild);
    }
    return buildSetting;
  }

  @VisibleForTesting
  public static StarlarkOptionsParser newStarlarkOptionsParserForTesting(
      SkyframeExecutor skyframeExecutor,
      Reporter reporter,
      PathFragment relativeWorkingDirectory,
      OptionsParser nativeOptionsParser) {
    return new StarlarkOptionsParser(
        skyframeExecutor, relativeWorkingDirectory, reporter, nativeOptionsParser);
  }

  @VisibleForTesting
  public void setResidueForTesting(List<String> residue) {
    nativeOptionsParser.setResidue(residue);
  }

  @VisibleForTesting
  public OptionsParser getNativeOptionsParserFortesting() {
    return nativeOptionsParser;
  }
}
