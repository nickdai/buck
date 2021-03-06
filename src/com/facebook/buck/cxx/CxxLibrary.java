/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.android.AndroidPackageable;
import com.facebook.buck.android.AndroidPackageableCollector;
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.model.Pair;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * An action graph representation of a C/C++ library from the target graph, providing the
 * various interfaces to make it consumable by C/C++ preprocessing and native linkable rules.
 */
public class CxxLibrary extends AbstractCxxLibrary {

  private final BuildRuleParams params;
  private final BuildRuleResolver ruleResolver;
  private final ImmutableMultimap<CxxSource.Type, String> exportedPreprocessorFlags;
  private final ImmutableList<String> linkerFlags;
  private final ImmutableList<Pair<String, ImmutableList<String>>> platformLinkerFlags;
  private final boolean linkWhole;
  private final Optional<String> soname;

  public CxxLibrary(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      ImmutableMultimap<CxxSource.Type, String> exportedPreprocessorFlags,
      ImmutableList<String> linkerFlags,
      ImmutableList<Pair<String, ImmutableList<String>>> platformLinkerFlags,
      boolean linkWhole,
      Optional<String> soname) {
    super(params, pathResolver);
    this.params = params;
    this.ruleResolver = ruleResolver;
    this.exportedPreprocessorFlags = exportedPreprocessorFlags;
    this.linkerFlags = linkerFlags;
    this.platformLinkerFlags = platformLinkerFlags;
    this.linkWhole = linkWhole;
    this.soname = soname;
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(CxxPlatform cxxPlatform) {
    BuildRule rule = CxxDescriptionEnhancer.requireBuildRule(
        params,
        ruleResolver,
        cxxPlatform.asFlavor(),
        CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR);
    Preconditions.checkState(rule instanceof SymlinkTree);
    SymlinkTree symlinkTree = (SymlinkTree) rule;
    return CxxPreprocessorInput.builder()
        .setRules(ImmutableSet.of(symlinkTree.getBuildTarget()))
        .setPreprocessorFlags(exportedPreprocessorFlags)
        .setIncludes(
            ImmutableCxxHeaders.builder()
                .putAllNameToPathMap(symlinkTree.getLinks())
                .putAllFullNameToPathMap(symlinkTree.getFullLinks())
                .build())
        .setIncludeRoots(ImmutableList.of(symlinkTree.getRoot()))
        .build();
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type) {

    // Build up the arguments used to link this library.  If we're linking the
    // whole archive, wrap the library argument in the necessary "ld" flags.
    final BuildRule libraryRule;
    ImmutableList.Builder<String> linkerArgsBuilder = ImmutableList.builder();
    linkerArgsBuilder.addAll(linkerFlags);
    linkerArgsBuilder.addAll(
        CxxDescriptionEnhancer.getPlatformFlags(
            platformLinkerFlags,
            cxxPlatform.asFlavor().toString()));
    if (type == Linker.LinkableDepType.SHARED) {
      Path sharedLibraryPath = CxxDescriptionEnhancer.getSharedLibraryPath(
          getBuildTarget(),
          cxxPlatform);
      libraryRule = CxxDescriptionEnhancer.requireBuildRule(
          params,
          ruleResolver,
          cxxPlatform.asFlavor(),
          CxxDescriptionEnhancer.SHARED_FLAVOR);
      linkerArgsBuilder.add(sharedLibraryPath.toString());
    } else {
      libraryRule = CxxDescriptionEnhancer.requireBuildRule(
          params,
          ruleResolver,
          cxxPlatform.asFlavor(),
          CxxDescriptionEnhancer.STATIC_FLAVOR);
      Path staticLibraryPath = CxxDescriptionEnhancer.getStaticLibraryPath(
          getBuildTarget(),
          cxxPlatform.asFlavor());
      if (linkWhole) {
        Linker linker = cxxPlatform.getLd();
        linkerArgsBuilder.addAll(linker.linkWhole(staticLibraryPath.toString()));
      } else {
        linkerArgsBuilder.add(staticLibraryPath.toString());
      }
    }
    final ImmutableList<String> linkerArgs = linkerArgsBuilder.build();

    return new NativeLinkableInput(
        ImmutableList.<SourcePath>of(new BuildTargetSourcePath(libraryRule.getBuildTarget())),
        linkerArgs);
  }

  @Override
  public PythonPackageComponents getPythonPackageComponents(CxxPlatform cxxPlatform) {
    String sharedLibrarySoname =
        soname.or(CxxDescriptionEnhancer.getSharedLibrarySoname(getBuildTarget(), cxxPlatform));
    BuildRule sharedLibraryBuildRule = CxxDescriptionEnhancer.requireBuildRule(
        params,
        ruleResolver,
        cxxPlatform.asFlavor(),
        CxxDescriptionEnhancer.SHARED_FLAVOR);
    return new PythonPackageComponents(
        /* modules */ ImmutableMap.<Path, SourcePath>of(),
        /* resources */ ImmutableMap.<Path, SourcePath>of(),
        /* nativeLibraries */ ImmutableMap.<Path, SourcePath>of(
            Paths.get(sharedLibrarySoname),
            new BuildTargetSourcePath(sharedLibraryBuildRule.getBuildTarget())));
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables() {
    return AndroidPackageableCollector.getPackageableRules(params.getDeps());
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    collector.addNativeLinkable(this);
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform) {
    String sharedLibrarySoname =
        soname.or(CxxDescriptionEnhancer.getSharedLibrarySoname(getBuildTarget(), cxxPlatform));
    BuildRule sharedLibraryBuildRule = CxxDescriptionEnhancer.requireBuildRule(
        params,
        ruleResolver,
        cxxPlatform.asFlavor(),
        CxxDescriptionEnhancer.SHARED_FLAVOR);
    return ImmutableMap.<String, SourcePath>of(
        sharedLibrarySoname,
        new BuildTargetSourcePath(sharedLibraryBuildRule.getBuildTarget()));
  }

}
