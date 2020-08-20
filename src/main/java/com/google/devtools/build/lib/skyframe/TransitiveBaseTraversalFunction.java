// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.InconsistentFilesystemException;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.AdvertisedProviderSet;
import com.google.devtools.build.lib.packages.Aspect;
import com.google.devtools.build.lib.packages.AspectDefinition;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.BuildFileNotFoundException;
import com.google.devtools.build.lib.packages.DependencyFilter;
import com.google.devtools.build.lib.packages.InputFile;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.OutputFile;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.PackageGroup;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.ValueOrException2;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * This class can be extended to define {@link SkyFunction}s that traverse a target and its
 * transitive dependencies and return values based on that traversal.
 *
 * <p>The {@code TProcessedTargets} type parameter represents the result of processing a target and
 * its transitive dependencies.
 *
 * <p>{@code TransitiveBaseTraversalFunction} asks for one to be constructed via {@link
 * #processTarget}, and then asks for it to be updated based on the current target's attributes'
 * dependencies via {@link #processDeps}, and then asks for it to be updated based on the current
 * target' aspects' dependencies via {@link #processDeps}. Finally, it calls {@link
 * #computeSkyValue} with the {#code ProcessedTargets} to get the {@link SkyValue} to return.
 */
abstract class TransitiveBaseTraversalFunction<ProcessedTargetsT> implements SkyFunction {
  /**
   * Returns a {@link SkyKey} corresponding to the traversal of a target specified by {@code label}
   * and its transitive dependencies.
   *
   * <p>Extenders of this class should implement this function to return a key with their
   * specialized {@link SkyFunction}'s name.
   *
   * <p>{@link TransitiveBaseTraversalFunction} calls this for each dependency of a target, and
   * then gets their values from the environment.
   *
   * <p>The key's {@link SkyFunction} may throw at most {@link NoSuchPackageException} and
   * {@link NoSuchTargetException}. Other exception types are not handled by {@link
   * TransitiveBaseTraversalFunction}.
   */
  abstract SkyKey getKey(Label label);

  abstract ProcessedTargetsT processTarget(Label label, TargetAndErrorIfAny targetAndErrorIfAny);

  abstract void processDeps(
      ProcessedTargetsT processedTargets,
      EventHandler eventHandler,
      TargetAndErrorIfAny targetAndErrorIfAny,
      Iterable<Map.Entry<SkyKey, ValueOrException2<NoSuchPackageException, NoSuchTargetException>>>
          depEntries);

  /**
   * Returns a {@link SkyValue} based on the target and any errors it has, and the values
   * accumulated across it and a traversal of its transitive dependencies.
   */
  abstract SkyValue computeSkyValue(
      TargetAndErrorIfAny targetAndErrorIfAny, ProcessedTargetsT processedTargets);

  abstract Label argumentFromKey(SkyKey key);

  @Override
  public SkyValue compute(SkyKey key, Environment env)
      throws TransitiveBaseTraversalFunctionException, InterruptedException {
    Label label = argumentFromKey(key);
    TargetAndErrorIfAny targetAndErrorIfAny;
    try {
      targetAndErrorIfAny = loadTarget(env, label);
    } catch (NoSuchTargetException e) {
      throw new TransitiveBaseTraversalFunctionException(e);
    } catch (NoSuchPackageException e) {
      throw new TransitiveBaseTraversalFunctionException(e);
    }
    if (targetAndErrorIfAny == null) {
      return null;
    }

    // Process deps from attributes. It is essential that the last getValue(s) call we made to
    // skyframe for building this node was for the corresponding PackageValue.
    Collection<SkyKey> labelDepKeys = getLabelDepKeys(env, targetAndErrorIfAny);

    Map<SkyKey, ValueOrException2<NoSuchPackageException, NoSuchTargetException>> depMap =
        env.getValuesOrThrow(labelDepKeys, NoSuchPackageException.class,
            NoSuchTargetException.class);
    if (env.valuesMissing()) {
      return null;
    }
    // Process deps from attributes. It is essential that the second-to-last getValue(s) call we
    // made to skyframe for building this node was for the corresponding PackageValue.
    Iterable<SkyKey> labelAspectKeys =
        getStrictLabelAspectDepKeys(env, depMap, targetAndErrorIfAny);
    Set<Map.Entry<SkyKey, ValueOrException2<NoSuchPackageException, NoSuchTargetException>>>
        labelAspectEntries =
            env.getValuesOrThrow(
                    labelAspectKeys, NoSuchPackageException.class, NoSuchTargetException.class)
                .entrySet();
    if (env.valuesMissing()) {
      return null;
    }

    ProcessedTargetsT processedTargets = processTarget(label, targetAndErrorIfAny);
    processDeps(processedTargets, env.getListener(), targetAndErrorIfAny, depMap.entrySet());
    processDeps(processedTargets, env.getListener(), targetAndErrorIfAny, labelAspectEntries);

    return computeSkyValue(targetAndErrorIfAny, processedTargets);
  }

  Collection<SkyKey> getLabelDepKeys(
      SkyFunction.Environment env, TargetAndErrorIfAny targetAndErrorIfAny)
      throws InterruptedException {
    return Collections2.transform(getLabelDeps(targetAndErrorIfAny.getTarget()), this::getKey);
  }

  Iterable<SkyKey> getStrictLabelAspectDepKeys(
      SkyFunction.Environment env,
      Map<SkyKey, ValueOrException2<NoSuchPackageException, NoSuchTargetException>> depMap,
      TargetAndErrorIfAny targetAndErrorIfAny)
      throws InterruptedException {
    return getStrictLabelAspectKeys(targetAndErrorIfAny.getTarget(), depMap, env);
  }

  @Override
  public String extractTag(SkyKey skyKey) {
    return Label.print(argumentFromKey(skyKey));
  }

  /**
   * Return an Iterable of SkyKeys corresponding to the Aspect-related dependencies of target.
   *
   * <p>This method may return a precise set of aspect keys, but may need to request additional
   * dependencies from the env to do so.
   */
  private Iterable<SkyKey> getStrictLabelAspectKeys(
      Target target,
      Map<SkyKey, ValueOrException2<NoSuchPackageException, NoSuchTargetException>> depMap,
      Environment env)
      throws InterruptedException {
    if (!(target instanceof Rule)) {
      // Aspects can be declared only for Rules.
      return ImmutableList.of();
    }

    Rule rule = (Rule) target;

    List<SkyKey> depKeys = Lists.newArrayList();
    Multimap<Attribute, Label> transitions =
        rule.getTransitions(DependencyFilter.NO_NODEP_ATTRIBUTES);
    for (Attribute attribute : transitions.keySet()) {
      for (Aspect aspect : attribute.getAspects(rule)) {
        if (hasDepThatSatisfies(aspect, transitions.get(attribute), depMap, env)) {
          AspectDefinition.forEachLabelDepFromAllAttributesOfAspect(
              rule,
              aspect,
              DependencyFilter.ALL_DEPS,
              (aspectAttribute, aspectLabel) -> depKeys.add(getKey(aspectLabel)));
        }
      }
    }
    return depKeys;
  }

  @Nullable
  protected abstract AdvertisedProviderSet getAdvertisedProviderSet(
      Label toLabel,
      @Nullable ValueOrException2<NoSuchPackageException, NoSuchTargetException> toVal,
      Environment env)
      throws InterruptedException;

  private final boolean hasDepThatSatisfies(
      Aspect aspect,
      Iterable<Label> depLabels,
      Map<SkyKey, ValueOrException2<NoSuchPackageException, NoSuchTargetException>> fullDepMap,
      Environment env)
      throws InterruptedException {
    for (Label depLabel : depLabels) {
      AdvertisedProviderSet advertisedProviderSet =
          getAdvertisedProviderSet(depLabel, fullDepMap.get(depLabel), env);
      if (advertisedProviderSet != null
          && AspectDefinition.satisfies(aspect, advertisedProviderSet)) {
        return true;
      }
    }
    return false;
  }

  // TODO(bazel-team): Unify this logic with that in LabelVisitor, and possibly DependencyResolver.
  private static Collection<Label> getLabelDeps(Target target) throws InterruptedException {
    if (target instanceof OutputFile) {
      Rule rule = ((OutputFile) target).getGeneratingRule();
      List<Label> visibilityLabels = visitTargetVisibility(target);
      HashSet<Label> result = Sets.newHashSetWithExpectedSize(visibilityLabels.size() + 1);
      result.add(rule.getLabel());
      result.addAll(visibilityLabels);
      return result;
    } else if (target instanceof InputFile) {
      return new HashSet<>(visitTargetVisibility(target));
    } else if (target instanceof Rule) {
      List<Label> visibilityLabels = visitTargetVisibility(target);
      Collection<Label> ruleLabels = visitRule(target);
      HashSet<Label> result =
          Sets.newHashSetWithExpectedSize(visibilityLabels.size() + ruleLabels.size());
      result.addAll(visibilityLabels);
      result.addAll(ruleLabels);
      return result;
    } else if (target instanceof PackageGroup) {
      return new HashSet<>(visitPackageGroup((PackageGroup) target));
    } else {
      return ImmutableSet.of();
    }
  }

  private static Collection<Label> visitRule(Target target) throws InterruptedException {
    return ((Rule) target).getTransitions(DependencyFilter.NO_NODEP_ATTRIBUTES).values();
  }

  private static List<Label> visitTargetVisibility(Target target) {
    return target.getVisibility().getDependencyLabels();
  }

  private static List<Label> visitPackageGroup(PackageGroup packageGroup) {
    return packageGroup.getIncludes();
  }

  interface TargetAndErrorIfAny {

    boolean isPackageLoadedSuccessfully();

    @Nullable NoSuchTargetException getErrorLoadingTarget();

    Target getTarget();
  }

  @VisibleForTesting
  static class TargetAndErrorIfAnyImpl implements TargetAndErrorIfAny {

    private final boolean packageLoadedSuccessfully;
    @Nullable private final NoSuchTargetException errorLoadingTarget;
    private final Target target;

    @VisibleForTesting
    TargetAndErrorIfAnyImpl(
        boolean packageLoadedSuccessfully,
        @Nullable NoSuchTargetException errorLoadingTarget,
        Target target) {
      this.packageLoadedSuccessfully = packageLoadedSuccessfully;
      this.errorLoadingTarget = errorLoadingTarget;
      this.target = target;
    }

    @Override
    public boolean isPackageLoadedSuccessfully() {
      return packageLoadedSuccessfully;
    }

    @Override
    @Nullable
    public NoSuchTargetException getErrorLoadingTarget() {
      return errorLoadingTarget;
    }

    @Override
    public Target getTarget() {
      return target;
    }
  }

  @Nullable // Returns null if values are missing.
  @VisibleForTesting
  TargetAndErrorIfAny loadTarget(Environment env, Label label)
      throws NoSuchTargetException, NoSuchPackageException, InterruptedException {
    if (label.getName().contains("/")) {
      // This target is in a subdirectory, therefore it could potentially be invalidated by
      // a new BUILD file appearing in the hierarchy.
      PathFragment containingDirectory = getContainingDirectory(label);
      PackageIdentifier newPkgId =
          PackageIdentifier.create(
              label.getPackageIdentifier().getRepository(), containingDirectory);
      ContainingPackageLookupValue containingPackageLookupValue;
      try {
        containingPackageLookupValue =
            (ContainingPackageLookupValue)
                env.getValueOrThrow(
                    ContainingPackageLookupValue.key(newPkgId),
                    BuildFileNotFoundException.class,
                    InconsistentFilesystemException.class);
      } catch (InconsistentFilesystemException e) {
        throw new NoSuchTargetException(label, e.getMessage());
      }
      if (containingPackageLookupValue == null) {
        return null;
      }

      if (!containingPackageLookupValue.hasContainingPackage()) {
        // This means the label's package doesn't exist. E.g. there is no package 'a' and we are
        // trying to build the target for label 'a:b/foo'.
        throw new BuildFileNotFoundException(
            label.getPackageIdentifier(),
            "BUILD file not found on package path for '"
                + label.getPackageFragment().getPathString()
                + "'");
      }
      if (!containingPackageLookupValue
          .getContainingPackageName()
          .equals(label.getPackageIdentifier())) {
        throw new NoSuchTargetException(
            label,
            String.format(
                "Label '%s' crosses boundary of subpackage '%s'",
                label, containingPackageLookupValue.getContainingPackageName()));
      }
    }

    SkyKey packageKey = PackageValue.key(label.getPackageIdentifier());
    PackageValue packageValue =
        (PackageValue) env.getValueOrThrow(packageKey, NoSuchPackageException.class);
    if (env.valuesMissing() || packageValue == null) {
      return null;
    }

    Package pkg = packageValue.getPackage();
    Target target = pkg.getTarget(label.getName());
    NoSuchTargetException error = pkg.containsErrors() ? new NoSuchTargetException(target) : null;
    return new TargetAndErrorIfAnyImpl(
        /* packageLoadedSuccessfully= */ !pkg.containsErrors(), error, target);
  }

  private static PathFragment getContainingDirectory(Label label) {
    PathFragment pkg = label.getPackageFragment();
    String name = label.getName();
    return name.equals(".") ? pkg : pkg.getRelative(name).getParentDirectory();
  }

  /**
   * Used to declare all the exception types that can be wrapped in the exception thrown by
   * {@link TransitiveTraversalFunction#compute}.
   */
  static class TransitiveBaseTraversalFunctionException extends SkyFunctionException {
    /**
     * Used to propagate an error from a direct target dependency to the target that depended on
     * it.
     */
    public TransitiveBaseTraversalFunctionException(NoSuchPackageException e) {
      super(e, Transience.PERSISTENT);
    }

    /**
     * In nokeep_going mode, used to propagate an error from a direct target dependency to the
     * target that depended on it.
     *
     * <p>In keep_going mode, used the same way, but only for targets that could not be loaded at
     * all (we proceed with transitive loading on targets that contain errors).</p>
     */
    public TransitiveBaseTraversalFunctionException(NoSuchTargetException e) {
      super(e, Transience.PERSISTENT);
    }
  }
}
