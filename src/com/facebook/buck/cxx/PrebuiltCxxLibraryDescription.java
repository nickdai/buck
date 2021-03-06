/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.FlavorDomainException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.model.Pair;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Map;

public class PrebuiltCxxLibraryDescription
    implements Description<PrebuiltCxxLibraryDescription.Arg> {

  private static enum Type {
    SHARED,
  }

  private static final FlavorDomain<Type> LIBRARY_TYPE =
      new FlavorDomain<>(
          "C/C++ Library Type",
          ImmutableMap.of(
              CxxDescriptionEnhancer.SHARED_FLAVOR, Type.SHARED));

  public static final BuildRuleType TYPE = new BuildRuleType("prebuilt_cxx_library");

  private final FlavorDomain<CxxPlatform> cxxPlatforms;

  public PrebuiltCxxLibraryDescription(FlavorDomain<CxxPlatform> cxxPlatforms) {
    this.cxxPlatforms = cxxPlatforms;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  private <A extends Arg> BuildRule createSharedLibraryBuildRule(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      CxxPlatform cxxPlatform,
      A args) {

    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);

    BuildTarget target = params.getBuildTarget();
    String libDir = args.libDir.or("lib");
    String libName = args.libName.or(target.getShortNameOnly());
    String soname = args.soname.or(String.format("lib%s.so", libName));
    Path staticLibraryPath =
        target.getBasePath()
            .resolve(libDir)
            .resolve(String.format("lib%s.a", libName));

    // Otherwise, we need to build it from the static lib.
    BuildTarget sharedTarget =
        BuildTargets.extendFlavoredBuildTarget(
            params.getBuildTarget(),
            CxxDescriptionEnhancer.SHARED_FLAVOR);

    // If not, setup a single link rule to link it from the static lib.
    Path builtSharedLibraryPath = BuildTargets.getBinPath(sharedTarget, "%s").resolve(soname);
    return CxxLinkableEnhancer.createCxxLinkableBuildRule(
        cxxPlatform,
        params,
        pathResolver,
        /* extraCxxLdFlags */ ImmutableList.<String>of(),
        /* extraLdFlags */ ImmutableList.<String>of(),
        sharedTarget,
        Linker.LinkType.SHARED,
        Optional.of(soname),
        builtSharedLibraryPath,
        ImmutableList.<SourcePath>of(new PathSourcePath(staticLibraryPath)),
        Linker.LinkableDepType.SHARED,
        params.getDeps());
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {

    // See if we're building a particular "type" of this library, and if so, extract
    // it as an enum.
    Optional<Map.Entry<Flavor, Type>> type;
    Optional<Map.Entry<Flavor, CxxPlatform>> platform;
    try {
      type = LIBRARY_TYPE.getFlavorAndValue(params.getBuildTarget().getFlavors());
      platform = cxxPlatforms.getFlavorAndValue(params.getBuildTarget().getFlavors());
    } catch (FlavorDomainException e) {
      throw new HumanReadableException("%s: %s", params.getBuildTarget(), e.getMessage());
    }

    // If we *are* building a specific type of this lib, call into the type specific
    // rule builder methods.  Currently, we only support building a shared lib from the
    // pre-existing static lib, which we do here.
    if (type.isPresent()) {
      Preconditions.checkState(type.get().getValue() == Type.SHARED);
      Preconditions.checkState(platform.isPresent());
      return createSharedLibraryBuildRule(params, resolver, platform.get().getValue(), args);
    }

    // Otherwise, we return the generic placeholder of this library, that dependents can use
    // get the real build rules via querying the action graph.
    final BuildTarget target = params.getBuildTarget();

    boolean headerOnly = args.headerOnly.or(false);
    boolean provided = args.provided.or(false);
    boolean linkWhole = args.linkWhole.or(false);
    String libDir = args.libDir.or("lib");
    String libName = args.libName.or(target.getShortNameOnly());
    String soname = args.soname.or(String.format("lib%s.so", libName));

    Path staticLibraryPath =
        target.getBasePath()
            .resolve(libDir)
            .resolve(String.format("lib%s.a", libName));
    Path sharedLibraryPath =
        target.getBasePath()
            .resolve(libDir)
            .resolve(String.format("lib%s.so", libName));

    // Resolve all the target-base-path-relative include paths to their full paths.
    Function<String, Path> fullPathFn = new Function<String, Path>() {
      @Override
      public Path apply(String input) {
        return target.getBasePath().resolve(input);
      }
    };
    final ImmutableList<Path> includeDirs = FluentIterable
        .from(args.includeDirs.or(ImmutableList.of("include")))
        .transform(fullPathFn)
        .toList();

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    return new PrebuiltCxxLibrary(
        params,
        resolver,
        pathResolver,
        includeDirs,
        staticLibraryPath,
        sharedLibraryPath,
        args.linkerFlags.or(ImmutableList.<String>of()),
        args.platformLinkerFlags.get(),
        soname,
        headerOnly,
        linkWhole,
        provided);
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public Optional<ImmutableList<String>> includeDirs;
    public Optional<String> libName;
    public Optional<String> libDir;
    public Optional<Boolean> headerOnly;
    public Optional<Boolean> provided;
    public Optional<Boolean> linkWhole;
    public Optional<ImmutableList<String>> linkerFlags;
    public Optional<ImmutableList<Pair<String, ImmutableList<String>>>> platformLinkerFlags;
    public Optional<String> soname;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
  }

}
