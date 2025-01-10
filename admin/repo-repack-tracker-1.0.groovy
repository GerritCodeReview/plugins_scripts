// Copyright (C) 2024 The Android Open Source Project
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
import com.google.gerrit.extensions.annotations.Listen
import com.google.gerrit.extensions.annotations.PluginName
import com.google.gerrit.extensions.events.LifecycleListener
import com.google.gerrit.lifecycle.LifecycleModule
import com.google.gerrit.metrics.CallbackMetric1
import com.google.gerrit.metrics.Description
import com.google.gerrit.metrics.Field
import com.google.gerrit.metrics.MetricMaker
import com.google.gerrit.server.config.ConfigUtil
import com.google.gerrit.server.config.PluginConfig
import com.google.gerrit.server.config.PluginConfigFactory
import com.google.gerrit.server.git.LocalDiskRepositoryManager
import com.google.inject.Singleton
import org.eclipse.jgit.errors.RepositoryNotFoundException
import org.eclipse.jgit.lib.Constants

import javax.inject.Inject
import java.util.concurrent.TimeUnit

import static groovy.io.FileType.FILES

@Singleton
@Listen
class RepoRepackTracker implements LifecycleListener {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass()

  @Inject
  @PluginName
  String pluginName
  @Inject
  PluginConfigFactory configFactory
  @Inject
  RepoRepackTrackerMetrics repoRepackTrackerMetrics

  @Override
  void start() {
    PluginConfig pluginConfig = configFactory.getFromGerritConfig(pluginName)
    long repackStalenessAfter = ConfigUtil.getTimeUnit(
        pluginConfig.getString("considerRepackStaleAfter", "1h"),
        3600000L,
        TimeUnit.MILLISECONDS)
    long gcStalenessAfter = ConfigUtil.getTimeUnit(
        pluginConfig.getString("considerGcStaleAfter", "12h"),
        43200000L,
        TimeUnit.MILLISECONDS)

    repoRepackTrackerMetrics.setStaleness(gcStalenessAfter, repackStalenessAfter)
    String[] projects = pluginConfig.getStringList("project")
    projects.each { projectName ->
      repoRepackTrackerMetrics.projects.add(projectName as String)
    }
    repoRepackTrackerMetrics.addTriggers()

    logger.atInfo().log("Plugin %s started for %d projects (considering gc stale after %d minutes, repack after %d minutes)",
        pluginName,
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
  private static final TMP_PREFIX = "tmp"
  private static final TMP_SUFFIX = "_tmp"
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
    } catch (RepositoryNotFoundException rnfe){
      logger.atWarning().log("Could not open project %s, if the project was removed you should also update the plugin configuration.",  projectName)
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
    def dir = path.resolve(name.get()).toFile()
    if (dir.isDirectory()) {
      return dir
    }
    dir = path.resolve("${name.get()}${Constants.DOT_GIT_EXT}").toFile()
    if (dir.isDirectory()) {
      return dir
    }
    throw new RepositoryNotFoundException(dir)
  }

}

class RepoRepackTrackerModule extends LifecycleModule {
    protected void configure() {
        listener().to(RepoRepackTrackerMetrics)
        listener().to(RepoRepackTracker)
    }
}

modules = [RepoRepackTrackerModule]
