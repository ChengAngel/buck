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

package com.facebook.buck.shell;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class AbstractGenruleStep extends ShellStep {

  /**
   * Matches either a relative or fully-qualified build target wrapped in <tt>${}</tt>, unless the
   * <code>$</code> is preceded by a backslash.
   *
   * Given the input: $(exe //foo:bar), capturing groups are
   * 1: $(exe //foo:bar)
   * 2: exe
   * 3: //foo:bar
   * 4: //foo
   * 5: :bar
   * If we match against $(location :bar), the capturing groups are:
   * 1: $(location :bar)
   * 2: location
   * 3: :bar
   * 4: null
   * 5: :bar
   */
  @VisibleForTesting
  static final Pattern BUILD_TARGET_PATTERN = Pattern.compile(
      // We want a negative lookbehind to ensure we don't have a '\$', which is why this starts off
      // in such an interesting way.
      "(?<!\\\\)(\\$\\((exe|location)\\s+((\\/\\/[^:]*)?(:[^\\)]+))\\))"
  );

  private final BuildRule buildRule;
  private final CommandString commandString;
  private final ImmutableSortedSet<BuildRule> depsToSubstituteInCommandString;

  public AbstractGenruleStep(BuildRule buildRule,
      CommandString commandString,
      Set<BuildRule> depsToSubstituteInCommandString) {
    this.buildRule = Preconditions.checkNotNull(buildRule);
    this.commandString = Preconditions.checkNotNull(commandString);
    this.depsToSubstituteInCommandString = ImmutableSortedSet.copyOf(
        depsToSubstituteInCommandString);
  }

  public static class CommandString {
    private Optional<String> cmd;
    private Optional<String> bash;
    private Optional<String> cmdExe;

    public CommandString(Optional<String> cmd, Optional<String> bash, Optional<String> cmdExe) {
      this.cmd = Preconditions.checkNotNull(cmd);
      this.bash = Preconditions.checkNotNull(bash);
      this.cmdExe = Preconditions.checkNotNull(cmdExe);
    }
  }

  private String getFullyQualifiedName() {
    return buildRule.getFullyQualifiedName();
  }

  @Override
  public String getShortName() {
    return "genrule";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    // The priority sequence is
    //   "cmd.exe /c winCommand" (Windows Only)
    //   "/bin/bash -c shCommand" (Non-windows Only)
    //   "(/bin/bash -c) or (cmd.exe /c) cmd" (All platforms)
    String command;
    if (context.getPlatform() == Platform.WINDOWS) {
      String commandInUse;
      if (commandString.cmdExe.isPresent()) {
        commandInUse = commandString.cmdExe.get();
      } else if (commandString.cmd.isPresent()) {
        commandInUse = commandString.cmd.get();
      } else {
        throw new HumanReadableException("You must specify either cmd_exe or cmd for genrule %s.",
            getFullyQualifiedName());
      }
      command = replaceMatches(context.getProjectFilesystem(), commandInUse);
      return ImmutableList.of("cmd.exe", "/c", command);
    } else {
      String commandInUse;
      if (commandString.bash.isPresent()) {
        commandInUse = commandString.bash.get();
      } else if (commandString.cmd.isPresent()) {
        commandInUse = commandString.cmd.get();
      } else {
        throw new HumanReadableException("You must specify either bash or cmd for genrule %s.",
            getFullyQualifiedName());
      }
      command = replaceMatches(context.getProjectFilesystem(), commandInUse);
      return ImmutableList.of("/bin/bash", "-c", command);
    }
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    ImmutableMap.Builder<String, String> environmentVariablesBuilder = ImmutableMap.builder();

    addEnvironmentVariables(context, environmentVariablesBuilder);

    return environmentVariablesBuilder.build();
  }

  abstract protected void addEnvironmentVariables(ExecutionContext context,
      ImmutableMap.Builder<String, String> environmentVariablesBuilder);

  @Override
  protected boolean shouldPrintStderr(Verbosity verbosity) {
    return true;
  }

  /**
   * @return the cmd with binary and location build targets interpolated as either commands or the
   * location of the outputs of those targets.
   */
  @VisibleForTesting
  String replaceMatches(ProjectFilesystem filesystem, String command) {
    Matcher matcher = BUILD_TARGET_PATTERN.matcher(command);
    StringBuffer buffer = new StringBuffer();
    Map<String, BuildRule> fullyQualifiedNameToBuildRule = null;
    while (matcher.find()) {
      if (fullyQualifiedNameToBuildRule == null) {
        fullyQualifiedNameToBuildRule = Maps.newHashMap();
        for (BuildRule dep : depsToSubstituteInCommandString) {
          fullyQualifiedNameToBuildRule.put(dep.getFullyQualifiedName(), dep);
        }
      }

      String buildTarget = matcher.group(3);
      String base = matcher.group(4);
      if (base == null) {
        // This is a relative build target, so make it fully qualified.
        BuildTarget myBuildTarget = buildRule.getBuildTarget();
        buildTarget = String.format("//%s%s", myBuildTarget.getBasePath(), buildTarget);
      }
      BuildRule matchingRule = fullyQualifiedNameToBuildRule.get(buildTarget);
      if (matchingRule == null) {
        throw new HumanReadableException("No dep named %s for %s %s, cmd was %s",
            buildTarget, buildRule.getType().getName(), getFullyQualifiedName(), command);
      }

      String replacement;
      Buildable matchingBuildable = matchingRule.getBuildable();
      switch (matcher.group(2)) {
        case "exe":
          replacement = getExecutableReplacementFrom(filesystem, command, matchingBuildable);
          break;

        case "location":
          replacement = getLocationReplacementFrom(filesystem, matchingBuildable);
          break;

        default:
          throw new HumanReadableException("Unable to determine replacement for '%s' in target %s",
              matcher.group(2), getFullyQualifiedName());
      }

      // `replacement` may contain Windows style directory separator backslash (\), which will be
      // considered as escape character. Escape them.
      matcher.appendReplacement(buffer, replacement.replace("\\", "\\\\"));
    }
    matcher.appendTail(buffer);
    return buffer.toString();
  }

  private String getLocationReplacementFrom(ProjectFilesystem filesystem, Buildable matchingRule) {
    return filesystem.getPathRelativizer().apply(matchingRule.getPathToOutputFile());
  }

  /**
   * A build rule can be executable in one of two ways: either by being a file with the executable
   * bit set, or by the rule being a {@link com.facebook.buck.rules.BinaryBuildRule}.
   *
   * @param filesystem The project file system to resolve files with.
   * @param cmd The command being executed.
   * @param matchingRule The BuildRule which may or may not be an executable.
   * @return A string which can be inserted to cause matchingRule to be executed.
   */
  private String getExecutableReplacementFrom(
      ProjectFilesystem filesystem,
      String cmd,
      Buildable matchingRule) {
    if (matchingRule instanceof BinaryBuildRule) {
      return ((BinaryBuildRule) matchingRule).getExecutableCommand(filesystem);
    }

    File output = filesystem.getFileForRelativePath(matchingRule.getPathToOutputFile());
    if (output != null && output.exists() && output.canExecute()) {
      return output.getAbsolutePath();
    }

    throw new HumanReadableException(
        "%s must correspond to a binary rule or file in %s for %s %s",
        matchingRule,
        cmd,
        buildRule.getType().getName(),
        getFullyQualifiedName());
  }
}
