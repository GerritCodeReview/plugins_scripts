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
    long considerStaleAfter = ConfigUtil.getTimeUnit(
        pluginConfig.getString("considerStaleAfter", "1h"),
        3600000L,
        TimeUnit.MILLISECONDS)

    repoRepackTrackerMetrics.setConsiderStaleAfter(considerStaleAfter)
    pluginConfig.getStringList("project").each { projectName ->
      repoRepackTrackerMetrics.projects.add(projectName as String)
    }
    repoRepackTrackerMetrics.addTrigger()

    logger.atInfo().log("Plugin %s started (staleAfter %d minutes)",
        pluginName,
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
  private static final TMP_PREFIX = "tmp"
  private static final TMP_SUFFIX = "_tmp"
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

  void addMetricsTrigger(CallbackMetric1<String, Long> projectsAndGcMetric, List<String> projects) {
    metrics.newTrigger(
        projectsAndGcMetric, {
      if (projects.isEmpty()) {
        projectsAndGcMetric.forceCreate("")
      } else {
        projects.each { e ->
          projectsAndGcMetric.set(e, isRepackingRunningForProject(e))
        }
        projectsAndGcMetric.prune()
      }
    })
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

}

class RepoRepackTrackerModule extends LifecycleModule {
    protected void configure() {
        listener().to(RepoRepackTrackerMetrics)
        listener().to(RepoRepackTracker)
    }
}

modules = [RepoRepackTrackerModule]
