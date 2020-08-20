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

package com.google.devtools.build.lib.analysis.config;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.skylarkbuildapi.config.ConfigurationTransitionApi;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.skylarkinterface.StarlarkContext;
import com.google.devtools.build.lib.syntax.BaseFunction;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.StarlarkSemantics;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Implementation of {@link ConfigurationTransitionApi}.
 *
 * <p>Represents a configuration transition across a dependency edge defined in Starlark.
 */
public abstract class StarlarkDefinedConfigTransition implements ConfigurationTransitionApi {

  private final List<String> inputs;
  private final List<String> outputs;
  private final Location location;
  private final StoredEventHandler eventHandler;

  private StarlarkDefinedConfigTransition(
      List<String> inputs, List<String> outputs, Location location) {
    this.inputs = inputs;
    this.outputs = outputs;
    this.location = location;
    this.eventHandler = new StoredEventHandler();
  }

  /**
   * Returns true if this transition is for analysis testing. If true, then only attributes of rules
   * with {@code analysis_test=true} may use this transition object.
   */
  public abstract Boolean isForAnalysisTesting();

  /**
   * Returns the input option keys for this transition. Only option keys contained in this list will
   * be provided in the 'settings' argument given to the transition implementation function.
   */
  public List<String> getInputs() {
    return inputs;
  }

  /**
   * Returns the output option keys for this transition. The transition implementation function must
   * return a dictionary where the option keys exactly match the elements of this list.
   */
  public List<String> getOutputs() {
    return outputs;
  }
  

  /**
   * Returns the location of the Starlark code responsible for determining the transition's changed
   * settings for purposes of error reporting.
   */
  public Location getLocationForErrorReporting() {
    return location;
  }

  public StoredEventHandler getEventHandler() {
    return eventHandler;
  }

  /**
   * Given a map of a subset of the "previous" build settings, returns the changed build settings as
   * a result of applying this transition.
   *
   * @param previousSettings a map representing the previous build settings
   * @return a list of changed build setting maps; each element of the list represents a different
   *     child configuration (split transitions will have multiple elements in this list, other
   *     transitions should have a single element). Each build setting map is a map from build
   *     setting to target setting value; all other build settings will remain unchanged
   * @throws EvalException if there is an error evaluating the transition
   * @throws InterruptedException if evaluating the transition is interrupted
   */
  public abstract ImmutableList<Map<String, Object>> evaluate(
      Map<String, Object> previousSettings, StructImpl attributeMap)
      throws EvalException, InterruptedException;

  public static StarlarkDefinedConfigTransition newRegularTransition(
      BaseFunction impl,
      List<String> inputs,
      List<String> outputs,
      StarlarkSemantics semantics,
      StarlarkContext context) {
    return new RegularTransition(impl, inputs, outputs, semantics, context);
  }

  public static StarlarkDefinedConfigTransition newAnalysisTestTransition(
      Map<String, Object> changedSettings, Location location) {
    return new AnalysisTestTransition(changedSettings, location);
  }

  private static class AnalysisTestTransition extends StarlarkDefinedConfigTransition {
    private final Map<String, Object> changedSettings;

    public AnalysisTestTransition(Map<String, Object> changedSettings, Location location) {
      super(ImmutableList.of(), ImmutableList.copyOf(changedSettings.keySet()), location);
      this.changedSettings = changedSettings;
    }

    @Override
    public Boolean isForAnalysisTesting() {
      return true;
    }

    @Override
    public ImmutableList<Map<String, Object>> evaluate(
        Map<String, Object> previousSettings, StructImpl attributeMapper) {
      return ImmutableList.of(changedSettings);
    }

    @Override
    public void repr(SkylarkPrinter printer) {
      printer.append("<analysis_test_transition object>");
    }

    @Override
    public boolean equals(Object object) {
      if (object == this) {
        return true;
      }
      if (object instanceof AnalysisTestTransition) {
        AnalysisTestTransition otherTransition = (AnalysisTestTransition) object;
        return Objects.equals(otherTransition.getInputs(), this.getInputs())
            && Objects.equals(otherTransition.getOutputs(), this.getOutputs())
            && Objects.equals(otherTransition.changedSettings, this.changedSettings);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.getInputs(), this.getOutputs(), this.changedSettings);
    }
  }

  /** A transition with a user-defined implementation function. */
  public static class RegularTransition extends StarlarkDefinedConfigTransition {
    private final BaseFunction impl;
    private final StarlarkSemantics semantics;
    private final StarlarkContext starlarkContext;

    RegularTransition(
        BaseFunction impl,
        List<String> inputs,
        List<String> outputs,
        StarlarkSemantics semantics,
        StarlarkContext context) {
      super(inputs, outputs, impl.getLocation());
      this.impl = impl;
      this.semantics = semantics;
      this.starlarkContext = context;
    }

    @Override
    public Boolean isForAnalysisTesting() {
      return false;
    }

    /**
     * This method evaluates the implementation function of the transition.
     *
     * <p>In the case of a {@link
     * com.google.devtools.build.lib.analysis.config.transitions.PatchTransition}, the impl fxn
     * returns a {@link SkylarkDict} of option name strings to option value object.
     *
     * <p>In the case of {@link
     * com.google.devtools.build.lib.analysis.config.transitions.SplitTransition}, the impl fxn can
     * return either a {@link SkylarkDict} of String keys to {@link SkylarkDict} values. Or it can
     * return a list of {@link SkylarkDict}s in cases where the consumer doesn't care about
     * differentiating between the splits (i.e. accessing later via {@code ctx.split_attrs}).
     *
     * @param previousSettings a map representing the previous build settings
     * @param attributeMapper a map of attributes
     */
    // TODO(bazel-team): integrate dict-of-dicts return type with ctx.split_attr
    @Override
    public ImmutableList<Map<String, Object>> evaluate(
        Map<String, Object> previousSettings, StructImpl attributeMapper)
        throws EvalException, InterruptedException {
      Object result;
      try {
        result = evalFunction(impl, ImmutableList.of(previousSettings, attributeMapper));
      } catch (EvalException e) {
        throw new EvalException(impl.getLocation(), e.getMessage());
      }

      if (result instanceof SkylarkDict<?, ?>) {
        // If we're recieving an empty dictionary, it's an error. Even if a
        // transition function sometimes evaluates to a no-op, it needs to return the passed in
        // settings. Return early for now since better error reporting will happen in
        // {@link FunctionTransitionUtil#validateFunctionOutputsMatchesDeclaredOutputs}
        if (((SkylarkDict) result).isEmpty()) {
          return ImmutableList.of(ImmutableMap.of());
        }
        // TODO(bazel-team): integrate keys with ctx.split_attr. Currently ctx.split_attr always
        // keys on cpu value - we should be able to key on the keys returned here.
        try {
          Map<String, SkylarkDict> dictOfDict =
              ((SkylarkDict<?, ?>) result)
                  .getContents(
                      String.class, SkylarkDict.class, "dictionary of options dictionaries");
          ImmutableList.Builder<Map<String, Object>> builder = ImmutableList.builder();
          for (Map.Entry<String, SkylarkDict> entry : dictOfDict.entrySet()) {
            Map<String, Object> dict =
                entry.getValue().getContents(String.class, Object.class, "an option dictionary");
            builder.add(dict);
          }
          return builder.build();
        } catch (EvalException e) {
          // fall through
        }
        try {
          return ImmutableList.of(
              ((SkylarkDict<?, ?>) result)
                  .getContents(String.class, Object.class, "dictionary of options"));
        } catch (EvalException e) {
          throw new EvalException(impl.getLocation(), e.getMessage());
        }
      } else if (result instanceof SkylarkList<?>) {
        ImmutableList.Builder<Map<String, Object>> builder = ImmutableList.builder();
        try {
          for (SkylarkDict<?, ?> toOptions :
              ((SkylarkList<?>) result)
                  .getContents(SkylarkDict.class, "dictionary of options dictionaries")) {
            builder.add(toOptions.getContents(String.class, Object.class, "dictionary of options"));
          }
        } catch (EvalException e) {
          throw new EvalException(impl.getLocation(), e.getMessage());
        }
        return builder.build();
      } else {
        throw new EvalException(
            impl.getLocation(),
            "Transition function must return a dictionary or list of dictionaries.");
      }
    }

    @Override
    public void repr(SkylarkPrinter printer) {
      printer.append("<transition object>");
    }

    /** Evaluate the input function with the given argument, and return the return value. */
    private Object evalFunction(BaseFunction function, ImmutableList<Object> args)
        throws InterruptedException, EvalException {
      try (Mutability mutability = Mutability.create("eval_transition_function")) {
        Environment env =
            Environment.builder(mutability)
                .setSemantics(semantics)
                .setEventHandler(getEventHandler())
                .setStarlarkContext(starlarkContext)
                .build();

        return function.call(args, ImmutableMap.of(), null, env);
      }
    }

    @Override
    public boolean equals(Object object) {
      if (object == this) {
        return true;
      }
      if (object instanceof RegularTransition) {
        RegularTransition otherTransition = (RegularTransition) object;
        return Objects.equals(otherTransition.getInputs(), this.getInputs())
            && Objects.equals(otherTransition.getOutputs(), this.getOutputs())
            && Objects.equals(otherTransition.impl, this.impl);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.getInputs(), this.getOutputs(), this.impl);
    }
  }
}
