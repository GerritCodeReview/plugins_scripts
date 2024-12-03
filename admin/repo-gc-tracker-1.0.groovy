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
import com.google.gerrit.server.config.PluginConfig
import com.google.gerrit.server.config.PluginConfigFactory
import com.google.gerrit.server.git.GitRepositoryManager
import com.google.gerrit.server.git.WorkQueue
import com.google.inject.Singleton
import org.eclipse.jgit.errors.RepositoryNotFoundException
import org.eclipse.jgit.lib.Repository

import javax.inject.Inject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.function.BiConsumer

@Singleton
@Listen
class RepoGCTracker implements LifecycleListener {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass()
  private static final gcFile = "gc.pid"

  @Inject
  @PluginName
  String pluginName
  @Inject
  PluginConfigFactory configFactory
  @Inject
  GitRepositoryManager repoMgr
  @Inject
  WorkQueue workQueue
  @Inject
  RepoGCTrackerMetrics repoGCTrackerMetrics

  private ScheduledFuture<?> scheduledCheckerTask
  private String[] projectNames
  private Integer checkIntervalSec = 10

  @Override
  void start() {
    if (scheduledCheckerTask != null) {
      return;
    }
    PluginConfig pluginConfig = configFactory.getFromGerritConfig(pluginName)
    checkIntervalSec = pluginConfig.getInt("checkIntervalSec", checkIntervalSec)
    projectNames = pluginConfig.getStringList("project")

    scheduledCheckerTask = workQueue.getDefaultQueue().scheduleAtFixedRate({ checkGcRunningForProjects() }, checkIntervalSec, checkIntervalSec, TimeUnit.SECONDS)
    logger.atInfo().log("%s checker started for %d projects (checkIntervalSec=%d)", gcFile, projectNames.length, checkIntervalSec)
  }

  @Override
  void stop() {
    logger.atInfo().log("Plugin %s stopping all projects gc running checker ...", pluginName)
    scheduledCheckerTask?.cancel(true)
  }

  def checkGcRunningForProjects() {
    def threadNameOrig = Thread.currentThread().getName()
    try {
      Thread.currentThread().setName("isGCRunning checker")
      projectNames.each { String projectName ->
        logger.atFine().log("%s checking project %s", pluginName, projectName)
          try {
            repoMgr.openRepository(Project.nameKey(projectName)).with { Repository repo ->
              isGCRunningForProject(repo, projectName)
            }
          } catch (RepositoryNotFoundException rnfe){
            logger.atWarning().log("Plugin %s could not open project %s, if the project was removed you should also update the plugin configuration.", pluginName,  projectName)
          } catch (Exception e) {
            logger.atSevere().withCause(e).log("Plugin %s could not check project %s", pluginName,  projectName)
          }
      }
      repoGCTrackerMetrics.updateMetrics()
    } finally {
      Thread.currentThread().setName(threadNameOrig)
    }
  }

  private void isGCRunningForProject(Repository repo, String projectName) {
    def repoDir = repo.getDirectory()
    File gcPid = new File(repoDir, gcFile)
    def hasGc = (gcPid.exists()) ? 1L : 0L
    repoGCTrackerMetrics.gcRunning.put(projectName, hasGc)
  }
}

@Singleton
class RepoGCTrackerMetrics implements LifecycleListener {
  private static final NAME = "is_gc_running_per_project"
  private static final DESCRIPTION = "Check Garbage Collection is running for the project"

  @com.google.inject.Inject
  MetricMaker metrics

  CallbackMetric1<String, Long> projectsAndGcMetric
  final Map<String, Long> gcRunning = new ConcurrentHashMap()

  void updateMetrics(){
    callMetricsTrigger(projectsAndGcMetric, gcRunning)
  }

  private void callMetricsTrigger(CallbackMetric1<String, Long> projectsAndGcMetric, Map<String, Long> gcRunning) {
    metrics.newTrigger(
        projectsAndGcMetric, {
      if (gcRunning.isEmpty()) {
        projectsAndGcMetric.forceCreate("")
      } else {
        gcRunning.each { e ->
          projectsAndGcMetric.set(e.key, e.value)
        }
        projectsAndGcMetric.prune()
      }
    })
  }

  CallbackMetric1<String, Long> createCallbackMetric(String name, String description) {
    metrics.newCallbackMetric(
        name,
        Long.class,
        new Description(description).setGauge(),
        Field.ofString("repository_name", { it.projectName } as BiConsumer)
            .description(description)
            .build()
    )
  }

  void start() {
    projectsAndGcMetric = createCallbackMetric(NAME, DESCRIPTION)
    callMetricsTrigger(projectsAndGcMetric, gcRunning)
  }

  void stop() {
    projectsAndGcMetric.remove()
  }
}

class RepoGCTrackerModule extends LifecycleModule {
  protected void configure() {
    listener().to(RepoGCTrackerMetrics)
    listener().to(RepoGCTracker)
  }
}

modules = [RepoGCTrackerModule]
