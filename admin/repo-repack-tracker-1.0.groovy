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
  private static final DESCRIPTION_GC = "Check gc running for the project"
  private static final DESCRIPTION_REPACK = "Check repack running for the project"
  private static final NAME_GC = "is_gc_running_per_project"
  private static final NAME_REPACK = "is_repack_running_per_project"
  private static final GC_PID_FILE = "gc.pid"
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
  private long repackStalenessAfterMs
  private long gcStalenessAfterMs
  CallbackMetric1<String, Long> callbackMetricGc
  CallbackMetric1<String, Long> callbackMetricRepack
  List<String> projects

  @Override
  void start() {
    PluginConfig pluginConfig = configFactory.getFromGerritConfig(pluginName)
    long repackStalenessAfter = ConfigUtil.getTimeUnit(
        pluginConfig.getString("considerRepackStaleAfter", "60 minutes"),
        60L,
        TimeUnit.MINUTES)
    long gcStalenessAfter = ConfigUtil.getTimeUnit(
        pluginConfig.getString("considerGcStaleAfter", "720 minutes"),
        720L,
        TimeUnit.MINUTES)
    repackStalenessAfterMs = TimeUnit.MILLISECONDS.convert(repackStalenessAfter, TimeUnit.MINUTES)
    gcStalenessAfterMs = TimeUnit.MILLISECONDS.convert(gcStalenessAfter, TimeUnit.MINUTES)

    projects = pluginConfig.getStringList("project")

    callbackMetricGc = createCallbackMetric(NAME_GC, DESCRIPTION_GC)
    callbackMetricRepack = createCallbackMetric(NAME_REPACK, DESCRIPTION_REPACK)
    addMetricsTriggers()

    logger.atInfo().log("Plugin %s started for %d projects (considering gc stale after %d minutes, repack after %d minutes)",
        pluginName,
        projects.size(),
        gcStalenessAfter,
        repackStalenessAfter)
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

  void addMetricsTriggers() {
    metrics.newTrigger(
        callbackMetricRepack, {
      if (projects.isEmpty()) {
        callbackMetricRepack.forceCreate("")
      } else {
        projects.each { e ->
<<<<<<< PATCH SET (356d19 Add `is_gc_running_per_project_<repo_name>` metric)
          callbackMetricRepack.set(e, isRepackingRunningForProject(e))
||||||| BASE
          projectsAndGcMetric.set(e, isRepackingRunningForProject(e))
=======
          projectsAndGcMetric.set(e, checkRepackingRunningForProject(e))
>>>>>>> BASE      (e0be02 Added plugin repo-repack-tracker)
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
  }

  long isGCRunningForProject(String projectName) {
    def isGcRunning = 0L
    try {
      def repoDir = getRepoDir(projectName)
      def gcPid = new File(repoDir, GC_PID_FILE)
      isGcRunning = (gcPid.exists() && !isFileStale(gcPid))? 1L : 0L
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("Could not check project %s",  projectName)
    }
    isGcRunning
  }

  long checkRepackingRunningForProject(String projectName) {
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

  File getRepoDir(String projectName) {
    def name = Project.nameKey(projectName)
    def path = repoMgr.getBasePath(name)
    return path.resolve("${name.get()}${Constants.DOT_GIT_EXT}").toFile()
  }

  boolean hasRepackTmpFiles(folder) {
<<<<<<< PATCH SET (356d19 Add `is_gc_running_per_project_<repo_name>` metric)
    def oneHourAgo = System.currentTimeMillis() - repackStalenessAfterMs
||||||| BASE
    def oneHourAgo = System.currentTimeMillis() - considerStaleAfterMs
=======
    def modifiedCutoffTime = System.currentTimeMillis() - considerStaleAfterMs
>>>>>>> BASE      (e0be02 Added plugin repo-repack-tracker)
    def tmpFileFound = false

    folder.traverse(type: FILES, nameFilter: tmpFilter) { file ->
      if (file.lastModified() >= modifiedCutoffTime) {
        tmpFileFound = true
      }
    }
    return tmpFileFound
  }

  boolean isFileStale(File f) {
    def fileExpiry = f.lastModified() + gcStalenessAfterMs
    return System.currentTimeMillis() > fileExpiry
  }

  @Override
  void stop() {}
}

class RepoRepackTrackerModule extends LifecycleModule {
    protected void configure() {
        listener().to(RepoRepackTracker)
    }
}

modules = [RepoRepackTrackerModule]
