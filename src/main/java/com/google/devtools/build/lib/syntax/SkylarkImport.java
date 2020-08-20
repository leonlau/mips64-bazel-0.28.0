// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.syntax;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.LabelValidator;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec.VisibleForSerialization;
import java.io.Serializable;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Encapsulates the two syntactic variants of Starlark imports: absolute labels and relative labels.
 */
@AutoCodec
public class SkylarkImport implements Serializable {
  private final String importString;
  @Nullable private final Label importLabel;

  @VisibleForSerialization
  SkylarkImport(String importString, @Nullable Label importLabel) {
    this.importString = importString;
    this.importLabel = importLabel;
  }

  /** Returns the string originally used to specify the import (represents a label). */
  public String getImportString() {
    return importString;
  }

  /**
   * Given a {@link Label} representing the file that contains this import, returns a {@link Label}
   * representing the .bzl file to be imported.
   *
   * @throws IllegalStateException if this import takes the form of an absolute path.
   */
  public Label getLabel(Label containingFileLabel) {
    if (importLabel != null) {
      // When the import label contains no explicit repository identifier, we resolve it relative
      // to the repo of the containing file.
      return containingFileLabel.resolveRepositoryRelative(importLabel);
    }

    // The import target is relative to the containing package, not the containing directory
    // within the package.
    try {
      // This is for imports relative to the current repository, so repositoryMapping can be empty
      return containingFileLabel.getRelativeWithRemapping(getImportString(), ImmutableMap.of());
    } catch (LabelSyntaxException e) {
      // shouldn't happen because the parent label is assumed validated and the target string is
      // validated on construction
      throw new IllegalStateException(e);
    }
  }

  @Override
  public int hashCode() {
    return Objects.hash(getClass(), importString);
  }

  @Override
  public boolean equals(Object that) {
    if (this == that) {
      return true;
    }

    if (!(that instanceof SkylarkImport)) {
      return false;
    }

    return (that instanceof SkylarkImport)
        && Objects.equals(importString, ((SkylarkImport) that).importString);
  }

  /** Exception raised for syntactically-invalid Skylark load strings. */
  public static class SkylarkImportSyntaxException extends Exception {
    public SkylarkImportSyntaxException(String message) {
      super(message);
    }
  }

  @VisibleForTesting static final String INVALID_LABEL_PREFIX = "Invalid label: ";

  @VisibleForTesting
  static final String MUST_HAVE_BZL_EXT_MSG =
      "The label must reference a file with extension '.bzl'";

  @VisibleForTesting
  static final String EXTERNAL_PKG_NOT_ALLOWED_MSG =
      "Starlark files may not be loaded from the //external package";

  @VisibleForTesting
  static final String INVALID_PATH_SYNTAX =
      "First argument of 'load' must be a label and start with either '//', ':', or '@'";

  @VisibleForTesting static final String INVALID_TARGET_PREFIX = "Invalid target: ";

  /**
   * Creates and syntactically validates a {@link SkylarkImport} instance from a string.
   *
   * <p>There are four syntactic import variants: Absolute paths, relative paths, absolute labels,
   * and relative labels
   *
   * @throws SkylarkImportSyntaxException if the string is not a valid Skylark import.
   */
  public static SkylarkImport create(String importString) throws SkylarkImportSyntaxException {
    return create(importString, /* repositoryMapping= */ ImmutableMap.of());
  }

  /**
   * Creates and syntactically validates a {@link SkylarkImport} instance from a string.
   *
   * <p>There four syntactic import variants: Absolute paths, relative paths, absolute labels, and
   * relative labels
   *
   * <p>Absolute labels will have the repository portion of the label remapped if it is present in
   * {@code repositoryMapping}
   *
   * @throws SkylarkImportSyntaxException if the string is not a valid Skylark import.
   */
  public static SkylarkImport create(
      String importString, ImmutableMap<RepositoryName, RepositoryName> repositoryMapping)
      throws SkylarkImportSyntaxException {
    if (!importString.endsWith(".bzl")) {
      throw new SkylarkImportSyntaxException(MUST_HAVE_BZL_EXT_MSG);
    }

    if (importString.startsWith("//") || importString.startsWith("@")) {
      // Absolute label.
      Label importLabel;
      try {
        importLabel = Label.parseAbsolute(importString, false, repositoryMapping);
      } catch (LabelSyntaxException e) {
        throw new SkylarkImportSyntaxException(INVALID_LABEL_PREFIX + e.getMessage());
      }
      PackageIdentifier packageId = importLabel.getPackageIdentifier();
      if (packageId.equals(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER)) {
        throw new SkylarkImportSyntaxException(EXTERNAL_PKG_NOT_ALLOWED_MSG);
      }
      return new SkylarkImport(importString, importLabel);
    } else if (importString.startsWith(":")) {
      // Relative label. We require that relative labels use an explicit ':' prefix.
      String importTarget = importString.substring(1);
      String maybeErrMsg = LabelValidator.validateTargetName(importTarget);
      if (maybeErrMsg != null) {
        // Null indicates successful target validation.
        throw new SkylarkImportSyntaxException(INVALID_TARGET_PREFIX + maybeErrMsg);
      }
      return new SkylarkImport(importString, null);
    }

    throw new SkylarkImportSyntaxException(INVALID_PATH_SYNTAX);
  }
}
