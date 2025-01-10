// Copyright (C) 2025 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
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

import com.google.common.flogger.FluentLogger
import com.google.gerrit.entities.Project
import com.google.gerrit.extensions.annotations.*
import com.google.gerrit.extensions.events.LifecycleListener
import com.google.gerrit.lifecycle.LifecycleModule
import com.google.gerrit.metrics.*
import com.google.gerrit.server.config.*
import com.google.gerrit.server.git.LocalDiskRepositoryManager
import com.google.inject.Singleton
import org.eclipse.jgit.lib.Constants

import javax.inject.Inject
import java.util.concurrent.TimeUnit

import static groovy.io.FileType.FILES

@Singleton
@Listen
class RepoRepackTracker implements LifecycleListener {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass()
  private static final NAME = "is_repack_running_per_project"
  private static final DESCRIPTION = "Check repack running for the project"
  private static final GIT_PACK_FOLDER = "objects/pack"
  private static final TMP_PREFIX = "tmp_"
  private static final TMP_SUFFIX = ".tmp"

  @Inject
  @PluginName
  String pluginName
  @Inject
  PluginConfigFactory configFactory
  @Inject
  MetricMaker metrics
  @Inject
  LocalDiskRepositoryManager repoMgr

  private def tmpFilter = ~/^(${TMP_PREFIX}.*|.*${TMP_SUFFIX})$/
  private long considerStaleAfterMs
  CallbackMetric1<String, Long> projectsAndGcMetric
  List<String> projects

  @Override
  void start() {
    PluginConfig pluginConfig = configFactory.getFromGerritConfig(pluginName)
<<<<<<< PATCH SET (aa054c Add `is_gc_running_per_project_<repo_name>` metric)
    long repackStalenessAfter = ConfigUtil.getTimeUnit(
        pluginConfig.getString("considerRepackStaleAfter", "1h"),
        3600000L,
        TimeUnit.MILLISECONDS)
    long gcStalenessAfter = ConfigUtil.getTimeUnit(
        pluginConfig.getString("considerGcStaleAfter", "12h"),
        43200000L,
        TimeUnit.MILLISECONDS)
||||||| BASE
    long considerStaleAfter = ConfigUtil.getTimeUnit(
        pluginConfig.getString("considerStaleAfter", "1h"),
        3600000L,
        TimeUnit.MILLISECONDS)
=======
    long considerStaleAfter = ConfigUtil.getTimeUnit(
        pluginConfig.getString("considerStaleAfter", "60 minutes"),
        60L,
        TimeUnit.MINUTES)
    considerStaleAfterMs = TimeUnit.MILLISECONDS.convert(considerStaleAfter, TimeUnit.MINUTES)
>>>>>>> BASE      (a320e2 Added plugin repo-repack-tracker)

<<<<<<< PATCH SET (aa054c Add `is_gc_running_per_project_<repo_name>` metric)
    repoRepackTrackerMetrics.setStaleness(gcStalenessAfter, repackStalenessAfter)
    String[] projects = pluginConfig.getStringList("project")
    projects.each { projectName ->
      repoRepackTrackerMetrics.projects.add(projectName as String)
    }
    repoRepackTrackerMetrics.addTriggers()
||||||| BASE
    repoRepackTrackerMetrics.setConsiderStaleAfter(considerStaleAfter)
    pluginConfig.getStringList("project").each { projectName ->
      repoRepackTrackerMetrics.projects.add(projectName as String)
    }
    repoRepackTrackerMetrics.addTrigger()
=======
    projects = pluginConfig.getStringList("project")
    projectsAndGcMetric = createCallbackMetric(NAME, DESCRIPTION)
    addMetricsTrigger(projectsAndGcMetric, projects)
>>>>>>> BASE      (a320e2 Added plugin repo-repack-tracker)

    logger.atInfo().log("Plugin %s started for %d projects (considering gc stale after %d minutes, repack after %d minutes)",
        pluginName,
<<<<<<< PATCH SET (aa054c Add `is_gc_running_per_project_<repo_name>` metric)
        projects.length,
        TimeUnit.MINUTES.convert(gcStalenessAfter, TimeUnit.MILLISECONDS),
        TimeUnit.MINUTES.convert(repackStalenessAfter, TimeUnit.MILLISECONDS)
    )
  }

  @Override
  void stop() {
    logger.atInfo().log("Plugin %s stopping...", pluginName)
  }
}

@Singleton
class RepoRepackTrackerMetrics implements LifecycleListener {
  @com.google.inject.Inject
  MetricMaker metrics
  @Inject
  LocalDiskRepositoryManager repoMgr

  private static final DESCRIPTION_REPACK = "Check repack running for the project"
  private static final DESCRIPTION_GC = "Check gc running for the project"
  private static final NAME_GC = "is_gc_running_per_project"
  private static final NAME_REPACK = "is_repack_running_per_project"
  private static final GIT_PACK_FOLDER = "objects/pack"

  private static final GC_PID_FILE = "gc.pid"
  private static final TMP_PREFIX = "tmp_"
  private static final TMP_SUFFIX = ".tmp"
  private def tmpFilter = ~/^(${TMP_PREFIX}.*|.*${TMP_SUFFIX})$/

  private static final FluentLogger logger = FluentLogger.forEnclosingClass()

  private long gcStaleAfter
  private long repackStaleAfter

  CallbackMetric1<String, Long> callbackMetricGc
  CallbackMetric1<String, Long> callbackMetricRepack
  List<String> projects = new ArrayList<>()

  void start() {
    callbackMetricGc = createCallbackMetric(NAME_GC, DESCRIPTION_GC)
    callbackMetricRepack = createCallbackMetric(NAME_REPACK, DESCRIPTION_REPACK)
  }

  void stop() {
    callbackMetricGc.remove()
    callbackMetricRepack.remove()
  }

  void setStaleness(long gcStaleAfter, long repackStaleAfter) {
    this.gcStaleAfter = gcStaleAfter
    this.repackStaleAfter = repackStaleAfter
  }

  void addTriggers(){
    metrics.newTrigger(
        callbackMetricRepack, {
      if (projects.isEmpty()) {
        callbackMetricRepack.forceCreate("")
      } else {
        projects.each { e ->
          callbackMetricRepack.set(e, isRepackingRunningForProject(e))
        }
        callbackMetricRepack.prune()
      }
    })
    metrics.newTrigger(
        callbackMetricGc, {
      if (projects.isEmpty()) {
        callbackMetricGc.forceCreate("")
      } else {
        projects.each { e ->
          callbackMetricGc.set(e, isGCRunningForProject(e))
        }
        callbackMetricGc.prune()
      }
    })
||||||| BASE
        TimeUnit.MINUTES.convert(considerStaleAfter, TimeUnit.MILLISECONDS))
  }

  @Override
  void stop() {
    logger.atInfo().log("Plugin %s stopping...", pluginName)
  }
}

@Singleton
class RepoRepackTrackerMetrics implements LifecycleListener {
  @com.google.inject.Inject
  MetricMaker metrics
  @Inject
  LocalDiskRepositoryManager repoMgr

  private static final NAME = "is_repack_running_per_project"
  private static final DESCRIPTION = "Check repack running for the project"
  private static final FluentLogger logger = FluentLogger.forEnclosingClass()
  private static final GIT_PACK_FOLDER = "objects/pack"
  private static final TMP_PREFIX = "tmp_"
  private static final TMP_SUFFIX = ".tmp"
  private def tmpFilter = ~/^(${TMP_PREFIX}.*|.*${TMP_SUFFIX})$/

  private long considerStaleAfter
  CallbackMetric1<String, Long> projectsAndGcMetric
  List<String> projects = new ArrayList<>()

  void start() {
    projectsAndGcMetric = createCallbackMetric(NAME, DESCRIPTION)
  }

  void stop() {
    projectsAndGcMetric.remove()
  }

  void setConsiderStaleAfter(long considerStaleAfter) {
    this.considerStaleAfter = considerStaleAfter
  }

  void addTrigger(){
    addMetricsTrigger(projectsAndGcMetric, projects)
=======
        considerStaleAfter)
>>>>>>> BASE      (a320e2 Added plugin repo-repack-tracker)
  }

  CallbackMetric1<String, Long> createCallbackMetric(String name, String description) {
    metrics.newCallbackMetric(
        name,
        Long.class,
        new Description(description).setGauge(),
        Field.ofProjectName("repository_name")
            .description(description)
            .build()
    )
  }

  long isGCRunningForProject(String projectName) {
    def isGcRunning = 0L
    try {
      def repoDir = getRepoDir(projectName)
      def gcPid = new File(repoDir, GC_PID_FILE)
      isGcRunning = (gcPid.exists() && !isFileStale(gcPid))? 1L : 0L
    } catch (RepositoryNotFoundException rnfe){
      logger.atWarning().log("Could not open project %s, if the project was removed you should also update the plugin configuration.",  projectName)
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("Could not check project %s",  projectName)
    }
    isGcRunning
  }

  long isRepackingRunningForProject(String projectName) {
    def isRepackRunning = 0L
    try {
      def repoDir = getRepoDir(projectName)
      def packDir = new File(repoDir, GIT_PACK_FOLDER)
      if (packDir.exists() && hasRepackTmpFiles(packDir)) {
        isRepackRunning = 1L
      }
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("Could not check project %s",  projectName)
    }
    isRepackRunning
  }

  boolean hasRepackTmpFiles(folder) {
    def oneHourAgo = System.currentTimeMillis() - repackStaleAfter
    def tmpFileFound = false

    folder.traverse(type: FILES, nameFilter: tmpFilter) { file ->
      if (file.lastModified() >= oneHourAgo) {
        tmpFileFound = true
        return false
      }
    }
    return tmpFileFound
  }

  boolean isFileStale(File f) {
    def fileExpiry = f.lastModified() + gcStaleAfter
    return System.currentTimeMillis() > fileExpiry
  }

  File getRepoDir(String projectName) {
    def name = Project.nameKey(projectName)
    def path = repoMgr.getBasePath(name)
    return path.resolve("${name.get()}${Constants.DOT_GIT_EXT}").toFile()
  }

<<<<<<< PATCH SET (aa054c Add `is_gc_running_per_project_<repo_name>` metric)
||||||| BASE
  boolean hasRepackTmpFiles(folder) {
    def oneHourAgo = System.currentTimeMillis() - considerStaleAfter
    def tmpFileFound = false

    folder.traverse(type: FILES, nameFilter: tmpFilter) { file ->
      if (file.lastModified() >= oneHourAgo) {
        tmpFileFound = true
        return false
      }
    }
    return tmpFileFound
  }

=======
  boolean hasRepackTmpFiles(folder) {
    def oneHourAgo = System.currentTimeMillis() - considerStaleAfterMs
    def tmpFileFound = false

    folder.traverse(type: FILES, nameFilter: tmpFilter) { file ->
      if (file.lastModified() >= oneHourAgo) {
        tmpFileFound = true
        return tmpFileFound
      }
    }
    return tmpFileFound
  }

  @Override
  void stop() {}
>>>>>>> BASE      (a320e2 Added plugin repo-repack-tracker)
}

class RepoRepackTrackerModule extends LifecycleModule {
    protected void configure() {
        listener().to(RepoRepackTracker)
    }
}

modules = [RepoRepackTrackerModule]
