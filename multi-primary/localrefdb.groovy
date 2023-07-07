// Copyright (C) 2023 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License")
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import com.google.gerrit.common.data.*
import com.google.gerrit.extensions.annotations.*
import com.google.inject.*
import com.google.gerrit.extensions.events.*
import com.google.gerrit.metrics.*
import org.kohsuke.args4j.*
import com.google.gerrit.server.git.*
import com.google.gerrit.entities.*
import org.eclipse.jgit.errors.*
import com.gerritforge.gerrit.globalrefdb.*
import org.eclipse.jgit.lib.*
import com.google.gerrit.sshd.*
import com.google.gerrit.lifecycle.*
import java.util.function.BiConsumer
import java.util.concurrent.*
import com.google.common.flogger.*
import java.util.regex.Pattern
import java.security.MessageDigest

abstract class BaseSshCommand extends SshCommand {
  void println(String msg, boolean verbose = false) {
    if (verbose) {
      stdout.println msg
    }
    stdout.flush()
  }

  void error(String msg) { println("[ERROR] $msg", true) }

  void warning(String msg) { println("[WARNING] $msg", true) }
}

@Singleton
class RefDbMetrics implements LifecycleListener {
  FluentLogger log = FluentLogger.forEnclosingClass()

  @Inject
  MetricMaker metrics

  CallbackMetric1<String, Integer> numRefsMetric
  final Map<String, Integer> projectsAndNumRefs = new ConcurrentHashMap()
  CallbackMetric1<String, Integer> sha1AllRefsMetric
  final Map<String, Integer> projectsAndSha1AllRefs = new ConcurrentHashMap()

  void setupTrigger(CallbackMetric1<String, Integer> refsMetric, Map<String, Integer> projecsAndRefs) {
    metrics.newTrigger(
        refsMetric, { ->
      if (projecsAndRefs.isEmpty()) {
        refsMetric.forceCreate("")
      } else {
        projecsAndRefs.each { e ->
          refsMetric.set(e.key, e.value)
        }
        refsMetric.prune()
      }
    })
  }

  CallbackMetric1<String, Integer> createCallbackMetric(String name, String description) {
    metrics.newCallbackMetric(
        name,
        Integer.class,
        new Description(description).setGauge(),
        Field.ofString("repository_name", { it.projectName } as BiConsumer)
            .description(description)
            .build())
  }

  void start() {
    numRefsMetric = createCallbackMetric("localrefdb/num_refs_per_project", "Number of local refs")

    setupTrigger(numRefsMetric, projectsAndNumRefs)


    sha1AllRefsMetric = createCallbackMetric("localrefdb/sha1_all_refs_per_project", "A SHA1 computed from combining all SHA1s of the repository.")

    setupTrigger(sha1AllRefsMetric, projectsAndSha1AllRefs)
  }

  void setValue(String project, int numRefs) {
    projectsAndNumRefs[project] = numRefs
    log.atInfo().log("Num refs per projects updated: %s", projectsAndNumRefs)
  }

  void stop() {
    numRefsMetric.remove()
    sha1AllRefsMetric.remove()
  }
}

@CommandMetaData(name = "count-refs", description = "Count the local number of refs, excluding user edits, and publish the value as 'num_refs' metric")
@RequiresCapability(GlobalCapability.ADMINISTRATE_SERVER)
class CountRefs extends BaseSshCommand {

  @Argument(index = 0, usage = "Project name", metaVar = "PROJECTS", required = true)
  String[] projects

  @Option(name = "--verbose", usage = "Display verbose logging")
  boolean verbose = false

  @Inject
  GitRepositoryManager repoMgr

  @Inject
  RefDbMetrics refdbMetrics

  SanitizeProjectName sanitizeProjectName = new SanitizeProjectName()

  public void run() {
    refdbMetrics.projectsAndNumRefs.clear()
    projects.each { project ->
      try {
        def projectName = Project.nameKey(project)
        repoMgr.openRepository(projectName).with { repo ->
          println ("Counting refs of project $project ...", verbose)
          def filteredRefs = repo.refDatabase.refs.findAll { ref -> !(ref.name.startsWith("refs/users/.*")) && !ref.symbolic }
          println ("Result: $project has ${filteredRefs.size()} refs", verbose)
          refdbMetrics.setValue(sanitizeProjectName.sanitize(project), filteredRefs.size())
        }
      } catch (RepositoryNotFoundException e) {
        error "Project $project not found"
      }
    }
  }
}

@CommandMetaData(name = "sha1-all-refs", description = "Combine all the local refs, excluding user edits, and publish the value as 'sha1_all_refs_per_project' metric")
@RequiresCapability(GlobalCapability.ADMINISTRATE_SERVER)
class Sha1AllRefs extends BaseSshCommand {

  @Argument(index = 0, usage = "Project names", metaVar = "PROJECT", required = true)
  String[] projects

  @Option(name = "--verbose", usage = "Display verbose logging")
  boolean verbose = false

  @Inject
  GitRepositoryManager repoMgr

  @Inject
  RefDbMetrics refdbMetrics

  SanitizeProjectName sanitizeProjectName = new SanitizeProjectName()

  public void run() {
    refdbMetrics.projectsAndSha1AllRefs.clear()
    projects.each { project ->
      try {
        def projectName = Project.nameKey(project)

        repoMgr.openRepository(projectName).with { repo ->
          def startTime = System.currentTimeMillis()
            println ("Adding refs of project $project ...", verbose)
          def filteredRefs = repo.refDatabase.refs.findAll { ref -> !(ref.name.startsWith("refs/users/.*")) && !ref.symbolic }
            println ("Result: $project has ${filteredRefs.size()} refs", verbose)
          def md = MessageDigest.getInstance("SHA-1")
          def sortingStartTime = System.currentTimeMillis()
          def sortedFilteredRefs = filteredRefs.sort { it.name }
            println("Sorting refs took ${System.currentTimeMillis() - sortingStartTime} millis", verbose)
          sortedFilteredRefs.each { ref -> md.update(ref.getObjectId().toString().getBytes("UTF-8")) }
          def sha1SumBytes = md.digest()
            println("MD Digest of sum of all SHA1 for project $project is: ${sha1SumBytes.encodeBase64().toString()}", verbose)
          def sha1Sum = truncateHashToInt(sha1SumBytes)
            println("Truncated Int representation of sum of all SHA1 for project $project is: $sha1Sum", verbose)
            println("Whole operation too ${System.currentTimeMillis() - startTime} millis", verbose)
          refdbMetrics.projectsAndSha1AllRefs.put(sanitizeProjectName.sanitize(project), sha1Sum)
        }
      } catch (RepositoryNotFoundException e) {
        error "Project $project not found"
      }
    }
  }

  // Source http://www.java2s.com/example/java-utility-method/sha1/sha1hashint-string-text-d6c0e.html
  static int truncateHashToInt(byte[] bytes) {
    int offset = bytes[bytes.length - 1] & 0x0f;
    return (bytes[offset] & (0x7f << 24)) | (bytes[offset + 1] & (0xff << 16)) | (bytes[offset + 2] & (0xff << 8)) | (bytes[offset + 3] & 0xff);
  }
}

class MetricsModule extends LifecycleModule {
  protected void configure() {
    listener().to(RefDbMetrics)
  }
}

class LocalRefDbCommandModule extends PluginCommandModule {
  protected void configureCommands() {
    command(CountRefs)
    command(Sha1AllRefs)
  }
}

class SanitizeProjectName {
  private static final Pattern METRIC_NAME_PATTERN = ~"[a-zA-Z0-9_-]+(/[a-zA-Z0-9_-]+)*"
  private static final Pattern INVALID_CHAR_PATTERN = ~"[^\\w-/]"
  private static final String REPLACEMENT_PREFIX = "_0x"

  static String sanitize(String name) {
    if (METRIC_NAME_PATTERN.matcher(name).matches() && !name.contains(REPLACEMENT_PREFIX)) {
      return name;
    }

    StringBuilder sanitizedName =
        new StringBuilder(name.substring(0, 1).replaceFirst("[^\\w-]", "_"))
    if (name.length() == 1) {
      return sanitizedName.toString()
    }

    String slashSanitizedName = name.substring(1).replaceAll("/[/]+", "/")
    if (slashSanitizedName.endsWith("/")) {
      slashSanitizedName = slashSanitizedName.substring(0, slashSanitizedName.length() - 1)
    }

    String replacementPrefixSanitizedName =
        slashSanitizedName.replaceAll(REPLACEMENT_PREFIX, REPLACEMENT_PREFIX + REPLACEMENT_PREFIX)

    for (int i = 0; i < replacementPrefixSanitizedName.length(); i++) {
      Character c = replacementPrefixSanitizedName.charAt(i)
      if (c.toString() ==~ INVALID_CHAR_PATTERN) {
        sanitizedName.append(REPLACEMENT_PREFIX)
        sanitizedName.append(c.toString().getBytes("UTF-8").encodeHex().toString().toUpperCase())
        sanitizedName.append('_')
      } else {
        sanitizedName.append(c)
      }
    }

    return sanitizedName.toString()
  }
}

modules = [MetricsModule, LocalRefDbCommandModule]
